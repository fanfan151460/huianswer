"""采集现有流式问答：同一次请求的完整回答和 evaluation 片段，不回查数据库。"""

import argparse
from datetime import datetime
import getpass
import hashlib
import json
import os
from pathlib import Path
import sys
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from collect_retrieval import write_json
from generation_pipeline_report import write_pipeline_report


def load_cases(dataset, limit, case_ids=None):
    path = dataset / "cases" / "dev.jsonl"
    cases = [json.loads(line) for line in path.read_text(encoding="utf-8-sig").splitlines()
             if line.strip()]
    if not cases or len({case["id"] for case in cases}) != len(cases):
        raise ValueError("开发题集为空或题号重复")
    catalog_path = dataset / "knowledge-bases.json"
    catalog = json.loads(catalog_path.read_text(encoding="utf-8-sig")) if catalog_path.exists() else {}
    kb_names = {kb["key"]: kb["name"] for kb in catalog.get("knowledge_bases", [])}
    for case in cases:
        case["expected_kb_names"] = [kb_names.get(key, key) for key in case.get("expected_kb_keys", [])]
        if not case.get("question") or not case.get("reference_answer"):
            raise ValueError(f"{case['id']} 缺少问题或参考答案")
        if any(item.get("role") not in ("user", "assistant") or not item.get("content")
               for item in case.get("history", [])):
            raise ValueError(f"{case['id']} 的历史格式不正确")
    if case_ids:
        missing = set(case_ids) - {case["id"] for case in cases}
        if missing:
            raise ValueError("题集不存在这些题号：" + ", ".join(sorted(missing)))
        cases = [case for case in cases if case["id"] in set(case_ids)]
    return cases[:limit] if limit else cases


def read_events(response):
    """按空行划分 SSE 事件；保留多行 data 中的换行，忽略连接注释。"""
    name, data = "message", []
    for raw in response:
        line = raw.decode("utf-8-sig").rstrip("\r\n")
        if not line:
            if data:
                yield name, "\n".join(data)
            name, data = "message", []
        elif not line.startswith(":"):
            field, separator, value = line.partition(":")
            if separator and value.startswith(" "):
                value = value[1:]
            if field == "event":
                name = value
            elif field == "data":
                data.append(value)
    # 未用空行结束的事件不当成完成事件；ask 会检查 done。


def ask(question, conversation_id, base_url, headers, timeout, require_trace=True):
    result = {"status": "error", "conversation_id": conversation_id, "generation_id": None,
              "response": "", "chunks": [], "retrieved_contexts": [], "error": None,
              "first_token_ms": None, "pipeline_trace": None, "generation_stats": None}
    payload = {"question": question}
    if conversation_id is not None:
        payload["conversationId"] = conversation_id
    request = Request(base_url + "/hui-ansWer/chat/stream",
                      data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
                      headers=headers)
    start = time.perf_counter()
    fragments, got_meta, got_evaluation, got_done = [], False, False, False
    try:
        with urlopen(request, timeout=timeout) as response:
            if response.headers.get_content_type() != "text/event-stream":
                raise RuntimeError("问答接口未返回 SSE，请检查登录状态和接口地址")
            for name, data in read_events(response):
                if name == "meta":
                    meta = json.loads(data)
                    received_id = meta.get("conversationId")
                    if not isinstance(received_id, int) or received_id <= 0:
                        raise ValueError("meta 缺少有效 conversationId")
                    if conversation_id is not None and received_id != conversation_id:
                        raise ValueError("响应会话 ID 与请求不一致")
                    result["conversation_id"] = received_id
                    result["generation_id"] = meta.get("generationId")
                    got_meta = True
                elif name == "evaluation":
                    if got_evaluation:
                        raise ValueError("同一次问答收到了重复的 evaluation 事件")
                    chunks = json.loads(data)
                    if not isinstance(chunks, list) or any(
                        not isinstance(chunk, dict) or not isinstance(chunk.get("content"), str)
                        or not chunk["content"].strip() for chunk in chunks
                    ):
                        raise ValueError("evaluation 必须是含 content 正文的片段数组")
                    result["chunks"] = chunks
                    result["retrieved_contexts"] = [chunk["content"] for chunk in chunks]
                    got_evaluation = True
                elif name == "evaluation_trace":
                    trace = json.loads(data)
                    if result["pipeline_trace"] is not None or not isinstance(trace, dict) or trace.get("version") != "generation-trace-v1":
                        raise ValueError("evaluation_trace 重复或格式不正确")
                    result["pipeline_trace"] = trace
                elif name == "evaluation_stats":
                    stats = json.loads(data)
                    if result["generation_stats"] is not None or not isinstance(stats, dict):
                        raise ValueError("evaluation_stats 重复或格式不正确")
                    result["generation_stats"] = stats
                elif name == "delta":
                    if result["first_token_ms"] is None and data.strip():
                        result["first_token_ms"] = round((time.perf_counter() - start) * 1000, 3)
                    fragments.append(data)
                elif name in ("error", "stopped"):
                    raise RuntimeError(f"服务端返回 {name} 事件：{data}")
                elif name == "done":
                    got_done = True
                    break
        if not got_done:
            raise RuntimeError("连接结束但未收到 done，回答可能不完整")
        if not got_meta:
            raise RuntimeError("未收到 meta，无法确定问答所属会话")
        if not got_evaluation:
            raise RuntimeError("未收到 evaluation，请启用 rag.evaluation.enabled 并重启服务")
        if require_trace and (result["pipeline_trace"] is None or result["generation_stats"] is None):
            raise RuntimeError("未收到过程评测事件，请重启加载本次代码并开启 rag.evaluation.enabled")
        if not "".join(fragments).strip():
            raise RuntimeError("回答正文为空")
        result["status"] = "success"
    except HTTPError as error:
        result["error"] = f"HTTP {error.code}，请检查服务、接口路径与登录状态"
    except Exception as error:
        result["error"] = f"{type(error).__name__}: {error}"
    result["response"] = "".join(fragments)
    result["evaluation_received"] = got_evaluation
    result["done_received"] = got_done
    result["http_elapsed_ms"] = round((time.perf_counter() - start) * 1000, 3)
    token = headers.get("token")
    if result["error"] and token:
        result["error"] = result["error"].replace(token, "[REDACTED]")
    return result


def collect_one(case, base_url, headers, timeout, require_trace=True):
    """每题新会话；多轮题通过真实问答预热，只向服务发送历史中的 user 输入。"""
    row = {
        "case_id": case["id"], "category": case["category"],
        "user_input": case["question"], "reference": case["reference_answer"],
        "generation_scorable": case.get("retrieval_scorable") is True,
        "expected_answer_points": case.get("expected_answer_points", []),
        "forbidden_claims": case.get("forbidden_claims", []),
        "history_fixture": case.get("history", []), "warmup_history": [], "warmup_traces": [],
        "acceptable_intents": case.get("acceptable_intents", []),
        "expected_kb_keys": case.get("expected_kb_keys", []),
        "expected_kb_names": case.get("expected_kb_names", []),
        "input_mode": "end_to_end_chat", "status": "error", "error": None,
        "conversation_id": None, "generation_id": None, "response": "",
        "retrieved_contexts": [], "chunks": [],
    }
    conversation_id = None
    for message in case.get("history", []):
        if message["role"] != "user":
            continue
        warmup = ask(message["content"], conversation_id, base_url, headers, timeout, require_trace)
        row["conversation_id"] = warmup["conversation_id"]
        if warmup["status"] != "success":
            row["error"] = "前置问答失败：" + str(warmup["error"])
            return row
        conversation_id = warmup["conversation_id"]
        row["warmup_traces"].append({"question": message["content"],
                                   "pipeline_trace": warmup["pipeline_trace"],
                                   "generation_stats": warmup["generation_stats"],
                                   "first_token_ms": warmup["first_token_ms"]})
        row["warmup_history"].extend([
            {"role": "user", "content": message["content"]},
            {"role": "assistant", "content": warmup["response"]},
        ])
    result = ask(case["question"], conversation_id, base_url, headers, timeout, require_trace)
    row.update(result)
    return row


def main():
    parser = argparse.ArgumentParser(description="采集现有流式问答，不回查数据库、不预先指定知识库")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--username", default=os.getenv("HUIANSWER_EVAL_USERNAME", "admin"))
    parser.add_argument("--dataset", type=Path, default=Path(__file__).resolve().parent / "xinglan-shop-v1")
    parser.add_argument("--limit", type=int, default=0, help="最多采集几道开发题，0 为全部20题")
    parser.add_argument("--case-ids", nargs="+", help="只采集指定题号，例如 XL-030")
    parser.add_argument("--timeout", type=float, default=180, help="HTTP读取无数据的超时秒数")
    parser.add_argument("--output-dir", type=Path)
    parser.add_argument("--allow-missing-trace", action="store_true",
                        help="兼容未加过程埋点的后端；仍记录首字时间，不要求 evaluation_trace/stats")
    parser.add_argument("--dry-run", action="store_true", help="只检查题集，不访问服务")
    args = parser.parse_args()
    if args.limit < 0 or not args.timeout > 0:
        parser.error("limit 不能为负数，timeout 必须大于0")
    dataset = args.dataset.resolve()
    cases = load_cases(dataset, args.limit, args.case_ids)
    warmups = sum(message["role"] == "user" for case in cases for message in case.get("history", []))
    print(f"开发题：{len(cases)}；前置问答：{warmups}；预计调用问答接口：{len(cases) + warmups} 次")
    if args.allow_missing_trace:
        print("兼容模式：允许缺少过程事件；仍要求真实正文、参考片段事件和 done，仅能比较已有计时。")
    if args.dry_run:
        print("题集检查通过；未调用接口，未创建结果文件。")
        return 0
    token = (os.getenv("HUIANSWER_EVAL_TOKEN") or getpass.getpass("粘贴登录 token（输入不显示）：")).strip()
    if not token:
        raise ValueError("token 不能为空")
    headers = {"Content-Type": "application/json", "Accept": "text/event-stream",
               "username": args.username, "token": token}
    run_id = datetime.now().strftime("%Y%m%d-%H%M%S-%f")
    directory = (args.output_dir or dataset / "runs" / "generation").resolve() / run_id
    directory.mkdir(parents=True, exist_ok=False)
    metadata = {
        "collection_version": "generation-trace-v1", "trace_required": not args.allow_missing_trace,
        "run_id": run_id, "input_mode": "end_to_end_chat", "base_url": args.base_url.rstrip("/"),
        "cases_sha256": hashlib.sha256((dataset / "cases" / "dev.jsonl").read_bytes()).hexdigest(),
        "case_ids": [case["id"] for case in cases], "planned_cases": len(cases),
        "completed_cases": 0, "successful_cases": 0, "failed_cases": 0, "status": "running",
        "history_mode": "live_warmup_user_messages; assistant_fixture_not_injected",
    }
    print(f"采集目录：{directory}")
    collected = []
    try:
        with (directory / "results.jsonl").open("x", encoding="utf-8") as output:
            for case in cases:
                row = collect_one(case, args.base_url.rstrip("/"), headers, args.timeout,
                                  require_trace=not args.allow_missing_trace)
                collected.append(row)
                output.write(json.dumps(row, ensure_ascii=False) + "\n")
                output.flush()
                metadata["completed_cases"] += 1
                success = row["status"] == "success"
                metadata["successful_cases" if success else "failed_cases"] += 1
                print(f"[{metadata['completed_cases']}/{len(cases)}] {case['id']} {row['status']}："
                      f"{len(row['retrieved_contexts'])} 段，{len(row['response'])} 字；首字={row.get('first_token_ms')} ms")
                if not success:
                    print(f"原因：{row['error']}。停止本轮，已完成结果保留，不自动重试。")
                    break
        metadata["status"] = "completed" if not metadata["failed_cases"] else "completed_with_errors"
    except KeyboardInterrupt:
        metadata["status"] = "interrupted"
        print("\n已中断，已完成的结果保留。")
    except BaseException:
        metadata["status"] = "failed"
        raise
    finally:
        write_json(directory / "run.json", metadata)
        write_pipeline_report(directory, collected)
    print(f"采集成功 {metadata['successful_cases']}，失败 {metadata['failed_cases']}；这不是回答正确率。")
    return 0 if metadata["status"] == "completed" else 1


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    try:
        sys.exit(main())
    except Exception as error:
        print(f"采集未完成：{error}", file=sys.stderr)
        sys.exit(1)
