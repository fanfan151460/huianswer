"""采集同一组复合问题的 whole/split 检索结果，复用 score_retrieval.py 评分。"""

import argparse
from datetime import datetime
import getpass
import hashlib
import json
import os
from pathlib import Path
import sys
import time

from collect_retrieval import load_cases, request_json, write_json


def collect_one(case, strategy, base_url, endpoint, headers, timeout, top_k):
    row = {
        "case_id": case["id"], "category": case["category"], "strategy": strategy,
        "user_input": case["question"], "original_question": case["question"],
        "reference": case["reference_answer"], "required_evidence": case["required_evidence"],
        "reference_contexts": [e["quote"] for e in case["required_evidence"]],
        "expected_kb_keys": case["expected_kb_keys"],
        "expected_needs": case.get("expected_needs", []),
        "expected_split": case.get("expected_split"),
        "input_mode": "single_turn_split_retrieval", "score_type": "rerank_score_higher_is_better",
        "status": "error", "error": None, "rewrite": None, "raw_response": None,
        "chunks": [], "candidates": [], "retrieved_contexts": [], "returned_chunk_count": 0,
        "http_elapsed_ms": None, "server_elapsed_ms": None,
    }
    started = time.perf_counter()
    try:
        # 标注、答案和预期知识库不发送给后端；不创建会话，不调用回答模型。
        payload = request_json(base_url + endpoint, headers, timeout,
                               {"question": row["user_input"], "choose": strategy})
        row["raw_response"] = payload
        if not isinstance(payload, dict) or payload.get("question") != row["user_input"]:
            raise ValueError("响应问题与请求不一致，请确认评测接口版本")
        if payload.get("errorMessage"):
            raise RuntimeError(payload["errorMessage"])
        rewrite = payload.get("rewriteResultDTO")
        row["rewrite"] = rewrite if isinstance(rewrite, dict) else None
        if not isinstance(rewrite, dict) or rewrite.get("fallback") is not False:
            raise ValueError("改写失败或未返回诊断标记，本次请求不按正常检索计分")
        if not isinstance(rewrite.get("rewrittenQuestion"), str) or not rewrite["rewrittenQuestion"].strip():
            raise ValueError("完整改写为空")
        questions = rewrite.get("subQuestions")
        if not isinstance(questions, list) or not questions or any(
                not isinstance(q, str) or not q.strip() for q in questions):
            raise ValueError("改写未返回有效的子问题集合")
        references = payload.get("references")
        if not isinstance(references, list):
            raise ValueError("接口没有返回 references 数组")
        candidates, ids = [], set()
        for rank, chunk in enumerate(references, start=1):
            if not isinstance(chunk, dict) or not isinstance(chunk.get("content"), str) or not chunk["content"].strip():
                raise ValueError("片段缺少有效正文")
            chunk_id = chunk.get("chunkId")
            if not isinstance(chunk_id, int) or isinstance(chunk_id, bool) or chunk_id <= 0 or chunk_id in ids:
                raise ValueError("片段 ID 无效或重复，请检查合并去重")
            ids.add(chunk_id)
            candidates.append({
                "rank": rank, "chunkId": chunk_id, "docId": chunk.get("docId"),
                "docName": chunk.get("docName"), "content": chunk["content"],
                "score": chunk.get("rerankScore"), "rrf_score": chunk.get("score"),
            })
        row["candidates"] = candidates
        row["returned_chunk_count"] = len(candidates)
        row["chunks"] = candidates[:top_k]
        row["retrieved_contexts"] = [chunk["content"] for chunk in row["chunks"]]
        row["status"] = "success"
    except Exception as error:
        row["error"] = f"{type(error).__name__}: {error}".replace(headers["token"], "[REDACTED]")
    row["http_elapsed_ms"] = round((time.perf_counter() - started) * 1000, 3)
    return row


def write_report(directory, rows):
    groups = {}
    for row in rows:
        groups.setdefault(row["case_id"], {})[row["strategy"]] = row
    pairs = {cid: group for cid, group in groups.items()
             if set(group) == {"whole", "split"} and all(r["status"] == "success" for r in group.values())}
    differences = [cid for cid, group in pairs.items()
                   if any(group["whole"]["rewrite"].get(key) != group["split"]["rewrite"].get(key)
                          for key in ("rewrittenQuestion", "subQuestions"))]
    summary = {
        "collected_requests": len(rows), "successful_requests": sum(r["status"] == "success" for r in rows),
        "failed_requests": sum(r["status"] != "success" for r in rows),
        "paired_success_cases": len(pairs), "rewrite_outputs_differ_case_ids": differences,
        "empty_retrieval": [{"case_id": r["case_id"], "strategy": r["strategy"]}
                            for r in rows if r["status"] == "success" and not r["chunks"]],
        "backup_rewrite": [{"case_id": r["case_id"], "strategy": r["strategy"]}
                          for r in rows if (r.get("rewrite") or {}).get("backupUsed")],
    }
    write_json(directory / "summary.json", summary)
    lines = ["# 拆分检索采集报告", "",
             "这是采集记录，不是检索准确率。片段与参考答案交给 score_retrieval.py 评分。", "",
             "两组分别调用改写；下表标记完整改写或子问题列表是否变化，变化时需人工核对，不能当成完全固定改写的纯拆分实验。",
             "只记录接口总耗时（含改写、意图路由、混合检索、重排）；接口未返回内部阶段状态和候选数。", "",
             "| 题号 | whole片段 | split片段 | 模型拆出几项 | 两次改写相同 | whole耗时 | split耗时 |",
             "| --- | ---: | ---: | ---: | --- | ---: | ---: |"]
    for cid, group in groups.items():
        whole, split = group.get("whole"), group.get("split")
        def count(row):
            return str(len(row["chunks"])) if row and row["status"] == "success" else "失败/未采集"
        def elapsed(row):
            return f"{row['http_elapsed_ms']:.0f} ms" if row else "未采集"
        size = len((split.get("rewrite") or {}).get("subQuestions") or []) if split else 0
        same = "是" if cid in pairs and cid not in differences else "否" if cid in pairs else "未比较"
        lines.append(f"| {cid} | {count(whole)} | {count(split)} | {size} | {same} | {elapsed(whole)} | {elapsed(split)} |")
    for row in rows:
        rewrite = row.get("rewrite") or {}
        lines.extend(["", f"## {row['case_id']} · {row['strategy']}", "", row["user_input"], "",
                      "**本题要覆盖的需求**", "", *["- " + x for x in row["expected_needs"]], "",
                      "**参考答案**", "", row["reference"], "",
                      "**完整改写**", "", rewrite.get("rewrittenQuestion") or "未取得", "",
                      "**模型拆分**", "", *["- " + str(q) for q in (rewrite.get("subQuestions") or [])], ""])
        if row["error"]:
            lines.extend(["采集失败：" + row["error"], ""])
        for chunk in row["chunks"]:
            lines.extend([f"### 片段 {chunk['rank']} · {chunk['docName']} · ID {chunk['chunkId']}", "",
                          chunk["content"], ""])
    path = directory / "report.md"
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"采集报告：{path}")
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=Path(__file__).resolve().parent / "xinglan-split-v1")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--endpoint", default="/api/evaluation/spilt", help="沿用当前 Java 接口的实际拼写")
    parser.add_argument("--username", default=os.getenv("HUIANSWER_EVAL_USERNAME", "admin"))
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--case-ids", nargs="+")
    parser.add_argument("--top-k", type=int, default=6)
    parser.add_argument("--timeout", type=float, default=90)
    parser.add_argument("--output-dir", type=Path)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    if args.limit < 0 or args.top_k <= 0 or args.timeout <= 0 or not args.endpoint.startswith("/"):
        parser.error("limit不能为负；top-k、timeout须大于0；endpoint以/开头")
    dataset = args.dataset.resolve()
    cases = load_cases(dataset, 0)
    if len({c["id"] for c in cases}) != len(cases):
        raise ValueError("题号重复")
    if args.case_ids:
        missing = set(args.case_ids) - {c["id"] for c in cases}
        if missing:
            raise ValueError("题集不存在这些题号：" + ", ".join(sorted(missing)))
        cases = [c for c in cases if c["id"] in set(args.case_ids)]
    cases = cases[:args.limit] if args.limit else cases
    if not cases or any(c.get("history") or not c.get("reference_answer") for c in cases):
        raise ValueError("本脚本只采集有参考答案的单轮题，不接受带历史题")
    print(f"题目 {len(cases)}；whole/split 各一次，共 {2 * len(cases)} 次检索请求。")
    print(f"接口：{args.base_url.rstrip('/')}{args.endpoint}；最终对齐 TopK={args.top_k}")
    print("不创建会话、不生成回答；每题先后顺序交替，不自动重试。")
    if args.dry_run:
        for case in cases:
            print(case["id"], case["category"], case["question"])
        print("输入校验通过，未访问服务，未生成结果。")
        return 0
    token = (os.getenv("HUIANSWER_EVAL_TOKEN") or getpass.getpass("粘贴登录 token（输入不显示）：")).strip()
    if not token:
        raise ValueError("token不能为空")
    headers = {"Content-Type": "application/json", "Accept": "application/json",
               "username": args.username, "token": token}
    directory = (args.output_dir or dataset / "runs").resolve() / datetime.now().strftime("%Y%m%d-%H%M%S-%f")
    directory.mkdir(parents=True, exist_ok=False)
    metadata = {
        "version": "split-retrieval-v1", "dataset": str(dataset),
        "cases_sha256": hashlib.sha256((dataset / "cases/dev.jsonl").read_bytes()).hexdigest(),
        "base_url": args.base_url.rstrip("/"), "endpoint": args.endpoint,
        "strategies": ["whole", "split"], "case_ids": [c["id"] for c in cases],
        "final_top_k": args.top_k, "planned_requests": 2 * len(cases),
        "input_mode": "single_turn_split_retrieval", "history_mode": "empty",
        "order": "whole_split_on_odd_cases_split_whole_on_even_cases", "status": "running",
    }
    print(f"采集目录：{directory}")
    rows = []
    try:
        with (directory / "results.jsonl").open("x", encoding="utf-8") as output:
            for index, case in enumerate(cases):
                strategies = ["whole", "split"] if index % 2 == 0 else ["split", "whole"]
                for strategy in strategies:
                    row = collect_one(case, strategy, args.base_url.rstrip("/"), args.endpoint,
                                      headers, args.timeout, args.top_k)
                    rows.append(row)
                    output.write(json.dumps(row, ensure_ascii=False) + "\n")
                    output.flush()
                    size = len((row.get("rewrite") or {}).get("subQuestions") or [])
                    print(f"[{len(rows)}/{metadata['planned_requests']}] {case['id']} {strategy} "
                          f"{row['status']}：{len(row['chunks'])} 段，改写拆出 {size} 项，{row['http_elapsed_ms']:.0f} ms")
                    if row["status"] != "success":
                        print("原因：" + row["error"])
                        print("停止本轮，已有结果保留；不自动重试。")
                        break
                if rows[-1]["status"] != "success":
                    break
        metadata["status"] = "completed" if len(rows) == metadata["planned_requests"] and all(
            r["status"] == "success" for r in rows) else "completed_with_errors"
    except KeyboardInterrupt:
        metadata["status"] = "interrupted"
        print("已中断，已有结果保留。")
    except BaseException:
        metadata["status"] = "failed"
        raise
    finally:
        metadata["completed_requests"] = len(rows)
        write_json(directory / "run.json", metadata)
        summary = write_report(directory, rows)
    print(f"采集成功 {summary['successful_requests']}，失败 {summary['failed_requests']}；"
          f"可配对 {summary['paired_success_cases']} 题。这不是检索准确率。")
    return 0 if metadata["status"] == "completed" else 1


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    try:
        sys.exit(main())
    except Exception as error:
        print(f"采集未完成：{error}", file=sys.stderr)
        sys.exit(1)
