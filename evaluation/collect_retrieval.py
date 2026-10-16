"""批量采集检索结果。只使用 Python 标准库，评分在后续步骤完成。"""

import argparse
import getpass
import hashlib
import json
import os
from datetime import datetime
from pathlib import Path
import sys
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen


SCORE_TYPES = {
    "vector": "cosine_distance_lower_is_better",
    "searchKeyWord": "keyword_score_higher_is_better",
    "mixSearch": "rrf_score_higher_is_better",
    "rerank": "rerank_score_higher_is_better",
}


def request_json(url, headers, timeout, body=None):
    """body 为空时 GET，否则发送 POST JSON；同时处理 HTTP 和业务错误。"""
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    request = Request(url, data=data, headers=headers)
    try:
        with urlopen(request, timeout=timeout) as response:
            payload = json.loads(response.read().decode("utf-8-sig"))
    except HTTPError as error:
        # 不输出请求头，避免把 token 写入终端或结果文件。
        raise RuntimeError(f"HTTP {error.code}，请检查服务、接口路径与登录状态") from error

    # 知识库列表使用 Result 封装；评测接口当前直接返回 AssessResp。
    if isinstance(payload, dict) and "code" in payload:
        if str(payload["code"]) != "0":
            raise RuntimeError(f"业务错误 {payload['code']}：{payload.get('message') or '请求失败'}")
        return payload.get("data")
    return payload


def load_cases(dataset, limit):
    """只选开发集中可评价检索的题；多轮题使用题库给出的独立完整问题。"""
    cases_path = dataset / "cases" / "dev.jsonl"
    cases = [
        json.loads(line)
        for line in cases_path.read_text(encoding="utf-8-sig").splitlines()
        if line.strip()
    ]
    selected = [case for case in cases if case.get("retrieval_scorable") is True]
    for case in selected:
        if not case.get("retrieval_query") or not case.get("expected_kb_keys"):
            raise ValueError(f"{case.get('id')} 缺少独立检索问题或预期知识库")
    if not selected:
        raise ValueError("开发集没有可采集的检索题")
    return selected[:limit] if limit else selected


def resolve_kb_ids(catalog, base_url, headers, timeout):
    """用逻辑名称匹配本机真实知识库 ID，不写死数据库自增 ID。"""
    rows = request_json(base_url + "/api/knowledge-base", headers, timeout)
    if not isinstance(rows, list):
        raise ValueError("知识库列表不是数组，请检查接口响应")
    mapping = {}
    for kb in catalog["knowledge_bases"]:
        matches = [row for row in rows if row.get("name") == kb["name"]]
        if len(matches) != 1:
            raise ValueError(f"知识库“{kb['name']}”找到 {len(matches)} 个，请核对名称或同名库")
        mapping[kb["key"]] = int(matches[0]["id"])
    return mapping


def collect_one(case, strategy, kb_ids, base_url, headers, timeout, top_k):
    """执行一题，保留原顺序和原分数；失败也返回一条结果记录。"""
    row = {
        "case_id": case["id"],
        "category": case["category"],
        "strategy": strategy,
        "user_input": case["retrieval_query"],
        "original_question": case["question"],
        "input_mode": "standalone_retrieval_query",
        "expected_kb_keys": case["expected_kb_keys"],
        "kb_ids": kb_ids,
        "reference": case["reference_answer"],
        "required_evidence": case["required_evidence"],
        "reference_contexts": [e["quote"] for e in case["required_evidence"]],
        "score_type": SCORE_TYPES[strategy],
        "status": "error",
        "retrieved_contexts": [],
        "chunks": [],
        "candidates": [],
        "returned_chunk_count": 0,
        "server_elapsed_ms": None,
        "http_elapsed_ms": None,
        "error": None,
    }
    start = time.perf_counter()
    try:
        # 对齐 ChunkAssessReq：字段名是 dbIds 和 choose。
        payload = request_json(
            base_url + "/api/evaluation/retrieval",
            headers,
            timeout,
            {"question": row["user_input"], "dbIds": kb_ids, "choose": strategy},
        )
        if not isinstance(payload, dict) or not isinstance(payload.get("chunks"), list):
            raise ValueError("评测接口应返回包含 chunks 数组的对象")
        candidates = []
        for rank, chunk in enumerate(payload["chunks"], start=1):
            if not isinstance(chunk, dict) or not isinstance(chunk.get("content"), str):
                raise ValueError("片段缺少 content 正文")
            if not chunk["content"].strip():
                raise ValueError("片段正文为空")
            candidates.append({
                "rank": rank,
                "docName": chunk.get("docName"),
                "docId": chunk.get("docId"),
                "chunkId": chunk.get("chunkId"),
                "content": chunk["content"],
                "score": chunk.get("score"),
            })
        row["candidates"] = candidates
        row["returned_chunk_count"] = len(candidates)
        row["chunks"] = candidates[:top_k]
        row["retrieved_contexts"] = [chunk["content"] for chunk in row["chunks"]]
        # 接口使用 System.nanoTime()，这里转成毫秒，不把它误当成毫秒。
        if payload.get("time") is not None:
            row["server_elapsed_ms"] = payload["time"] / 1_000_000
        row["status"] = "success"
    except Exception as error:
        row["error"] = f"{type(error).__name__}: {error}"
    row["http_elapsed_ms"] = round((time.perf_counter() - start) * 1000, 3)
    return row


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description="读取开发集，采集指定检索策略的片段和耗时")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--username", default=os.getenv("HUIANSWER_EVAL_USERNAME", "admin"))
    parser.add_argument("--dataset", type=Path, default=Path(__file__).resolve().parent / "xinglan-shop-v1")
    parser.add_argument("--strategies", nargs="+", choices=list(SCORE_TYPES),
                        default=["vector", "mixSearch", "rerank"])
    parser.add_argument("--limit", type=int, default=0, help="最多选几道题，0 为全部可检索开发题")
    parser.add_argument("--top-k", type=int, default=6, help="最终保存用于比较的前几个片段")
    parser.add_argument("--timeout", type=float, default=90, help="每个 HTTP 请求超时秒数")
    parser.add_argument("--output-dir", type=Path, help="结果父目录，默认在数据集的 runs 下")
    parser.add_argument("--dry-run", action="store_true", help="只检查题库和请求计划，不访问接口")
    args = parser.parse_args()
    if args.limit < 0 or args.top_k <= 0 or not args.timeout > 0:
        parser.error("limit 不能为负数，top-k 和 timeout 必须大于 0")

    dataset = args.dataset.resolve()
    cases = load_cases(dataset, args.limit)
    catalog = json.loads((dataset / "knowledge-bases.json").read_text(encoding="utf-8-sig"))
    known_keys = {kb["key"] for kb in catalog["knowledge_bases"]}
    if any(key not in known_keys for case in cases for key in case["expected_kb_keys"]):
        raise ValueError("题库引用了目录中不存在的知识库")
    strategies = list(dict.fromkeys(args.strategies))
    total = len(cases) * len(strategies)
    print(f"题数：{len(cases)}；策略：{', '.join(strategies)}；共 {total} 次评测请求")
    if args.dry_run:
        print(f"首题：{cases[0]['id']} {cases[0]['retrieval_query']}")
        print("题库检查通过，未访问服务，未生成采集文件。")
        return 0

    token = os.getenv("HUIANSWER_EVAL_TOKEN") or getpass.getpass("粘贴登录 token（输入不显示）：")
    token = token.strip()
    if not token:
        raise ValueError("token 不能为空")
    headers = {"Content-Type": "application/json", "Accept": "application/json",
               "username": args.username, "token": token}
    base_url = args.base_url.rstrip("/")
    kb_ids = resolve_kb_ids(catalog, base_url, headers, args.timeout)
    print("知识库 ID：" + json.dumps(kb_ids, ensure_ascii=False))

    # 每次新建一个运行目录，既有结果不覆盖；token 和请求头不写入文件。
    run_id = datetime.now().strftime("%Y%m%d-%H%M%S-%f")
    run_dir = (args.output_dir or dataset / "runs").resolve() / run_id
    run_dir.mkdir(parents=True, exist_ok=False)
    results_path = run_dir / "results.jsonl"
    metadata = {
        "run_id": run_id,
        "dataset_id": catalog["dataset_id"],
        "cases_sha256": hashlib.sha256((dataset / "cases" / "dev.jsonl").read_bytes()).hexdigest(),
        "base_url": base_url,
        "strategies": strategies,
        "case_ids": [case["id"] for case in cases],
        "kb_ids": kb_ids,
        "final_top_k": args.top_k,
        "timeout_seconds": args.timeout,
        "planned_requests": total,
        "completed_requests": 0,
        "successful_requests": 0,
        "failed_requests": 0,
        "empty_successful_requests": 0,
        "status": "running",
        "input_mode": "standalone_retrieval_query",
    }
    write_json(run_dir / "run.json", metadata)
    print(f"结果目录：{run_dir}")
    try:
        with results_path.open("x", encoding="utf-8") as output:
            for case in cases:
                scope = [kb_ids[key] for key in case["expected_kb_keys"]]
                for strategy in strategies:
                    row = collect_one(case, strategy, scope, base_url, headers, args.timeout, args.top_k)
                    output.write(json.dumps(row, ensure_ascii=False) + "\n")
                    output.flush()
                    metadata["completed_requests"] += 1
                    if row["status"] == "success":
                        metadata["successful_requests"] += 1
                        if not row["chunks"]:
                            metadata["empty_successful_requests"] += 1
                        detail = f"{len(row['chunks'])} 段，HTTP {row['http_elapsed_ms']:.0f} ms"
                    else:
                        metadata["failed_requests"] += 1
                        detail = row["error"]
                    print(f"[{metadata['completed_requests']}/{total}] {case['id']} {strategy} "
                          f"{row['status']}：{detail}")
        metadata["status"] = "completed" if not metadata["failed_requests"] else "completed_with_errors"
    except KeyboardInterrupt:
        metadata["status"] = "interrupted"
        print("\n已中断，先前完成的记录仍在结果文件中。")
    finally:
        write_json(run_dir / "run.json", metadata)
    print(f"采集结束：成功 {metadata['successful_requests']}，失败 {metadata['failed_requests']}，"
          f"成功但无片段 {metadata['empty_successful_requests']}。这些数量不是准确率。")
    return 0 if metadata["status"] == "completed" else 1


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    try:
        sys.exit(main())
    except Exception as error:
        print(f"采集未完成：{error}", file=sys.stderr)
        sys.exit(1)
