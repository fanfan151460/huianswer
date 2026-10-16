"""用已有 Ragas 环境评测完整回答；只读采集文件，不调用业务接口。"""

import argparse
import asyncio
from datetime import datetime
import getpass
import hashlib
import importlib.metadata
import json
import math
import os
from pathlib import Path
import statistics
import sys
import time
import urllib.request
from urllib.parse import urlsplit

# 复用检索评分脚本的判定记录工具；不会执行它的 main。
from score_retrieval import RecordingJudge, write_json
from openai import AsyncOpenAI
from ragas.cache import DiskCacheBackend
from ragas.llms import llm_factory
from ragas.metrics.collections import Faithfulness, FactualCorrectness


METRICS = ("faithfulness", "factual_correctness")


def latest_results():
    runs = Path(__file__).resolve().parent / "xinglan-shop-v1" / "runs" / "generation"
    paths = sorted(runs.glob("*/results.jsonl"))
    if not paths:
        raise ValueError("没有生成采集结果，请先运行 collect_generation.py")
    return paths[-1]


def load_rows(path, limit):
    rows = [json.loads(line) for line in path.read_text(encoding="utf-8-sig").splitlines() if line.strip()]
    if not rows or len({row["case_id"] for row in rows}) != len(rows):
        raise ValueError("采集结果为空或有重复题号")
    rows = rows[:limit] if limit else rows
    for row in rows:
        if row.get("input_mode") != "end_to_end_chat":
            raise ValueError("请使用生成采集结果，不能使用旧的独立检索结果")
        if not row.get("user_input") or not row.get("reference"):
            raise ValueError("采集记录缺少问题或参考答案")
        if not isinstance(row.get("retrieved_contexts"), list):
            raise ValueError("采集记录缺少 retrieved_contexts 数组")
    return rows


def faithfulness_contexts(row):
    """多轮题也提供真实预热历史；预设参考答案和助手历史样例都不会充当证据。"""
    contexts = list(row["retrieved_contexts"])
    history = row.get("warmup_history", [])
    if history:
        contexts.append("本次问答实际已有的对话历史（不是检索片段）：\n" +
                        "\n".join(f"{item['role']}: {item['content']}" for item in history))
    return contexts


async def score_one(row, metrics, judge, api_key):
    out = {key: row.get(key) for key in (
        "case_id", "category", "user_input", "reference", "response", "retrieved_contexts",
        "warmup_history", "conversation_id", "generation_id", "http_elapsed_ms",
        "expected_answer_points", "forbidden_claims",
    )}
    out["chunk_ids"] = [chunk.get("chunkId") for chunk in row.get("chunks", [])]
    out["metrics"] = {}
    if row["status"] != "success":
        out.update(status="error", error="采集失败：" + str(row.get("error")))
        return out
    if not row.get("generation_scorable"):
        out.update(status="manual_review", note="澄清、无答案、闲聊题按预期行为人工检查，不计入事实题均分")
        return out
    contexts = faithfulness_contexts(row)
    out["faithfulness_contexts"] = contexts
    for name, metric in metrics.items():
        judge.decisions.clear()
        result = {"status": "error", "score": None, "decisions": [], "error": None}
        start = time.perf_counter()
        try:
            if name == "faithfulness" and not contexts:
                result.update(status="skipped", error="没有检索资料或已有历史，忠实度不适用；事实正确性仍单独评分")
            else:
                if name == "faithfulness":
                    scored = await metric.ascore(user_input=row["user_input"], response=row["response"],
                                                 retrieved_contexts=contexts)
                else:
                    scored = await metric.ascore(response=row["response"], reference=row["reference"])
                value = float(scored.value)
                if math.isnan(value):
                    result.update(status="skipped", error="评审未提取到可验证事实，本项不计入均分")
                elif not math.isfinite(value) or not 0 <= value <= 1:
                    raise ValueError("评委未返回有效的0至1分数")
                else:
                    result.update(status="success", score=value)
        except Exception as error:
            message = f"{type(error).__name__}: {error}"
            result["error"] = message.replace(api_key, "[REDACTED]") if api_key else message
        result["decisions"] = list(judge.decisions)
        result["scoring_ms"] = round((time.perf_counter() - start) * 1000, 3)
        out["metrics"][name] = result
    out["status"] = "error" if any(item["status"] == "error" for item in out["metrics"].values()) else "success"
    return out


def summarize(rows):
    result = {"completed_records": len(rows),
              "manual_review_case_ids": [row["case_id"] for row in rows if row["status"] == "manual_review"],
              "failed_case_ids": [row["case_id"] for row in rows if row["status"] == "error"],
              "metrics": {}}
    for name in METRICS:
        values = [row["metrics"][name] for row in rows if name in row["metrics"]]
        successful = [item["score"] for item in values if item["status"] == "success"]
        result["metrics"][name] = {
            "mean_score": statistics.mean(successful) if successful else None,
            "scored_cases": len(successful),
            "failed_cases": sum(item["status"] == "error" for item in values),
            "skipped_cases": sum(item["status"] == "skipped" for item in values),
        }
    return result


async def run_scoring(args, rows, source, api_key):
    # 复用已有代理兼容方式，只影响本进程，不改系统代理和证书验证。
    proxies = urllib.request.getproxies()
    if os.name == "nt" and not os.getenv("HTTPS_PROXY") and not os.getenv("https_proxy"):
        http_proxy, https_proxy = proxies.get("http", ""), proxies.get("https", "")
        if http_proxy and https_proxy and urlsplit(http_proxy).netloc == urlsplit(https_proxy).netloc:
            os.environ["HTTPS_PROXY"] = http_proxy
    directory = source.parent / "scores" / datetime.now().strftime("%Y%m%d-%H%M%S-%f")
    directory.mkdir(parents=True, exist_ok=False)
    metadata = {
        "source": str(source), "source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
        "input_mode": "end_to_end_chat", "judge_model": args.model, "judge_base_url": args.base_url,
        "temperature": 0, "max_tokens": 4096, "metrics": list(METRICS),
        "factual_correctness": {"mode": "f1", "atomicity": "high", "coverage": "high"},
        "faithfulness_context_mode": "actual_retrieved_chunks_and_actual_warmup_history",
        "versions": {name: importlib.metadata.version(name) for name in ("ragas", "instructor", "openai")},
        "planned_records": len(rows), "completed_records": 0, "status": "running",
    }
    write_json(directory / "scoring_run.json", metadata)
    cache = DiskCacheBackend(cache_dir=str(directory / "judge-cache"))
    scored_rows = []
    print(f"生成评分目录：{directory}")
    try:
        async with AsyncOpenAI(api_key=api_key or "not-needed", base_url=args.base_url,
                               timeout=args.timeout, max_retries=1) as client:
            delegate = llm_factory(
                args.model, client=client, cache=cache, temperature=0, max_tokens=4096,
                max_retries=1, extra_body={"enable_thinking": False},
                system_prompt="你是严格的问答评测员。忠实度只判断给定上下文是否支持回答；"
                              "事实正确性只比较回答与参考答案的事实，不用外部知识补齐。"
                              "同义表达应视为等价。遵守每项评测的JSON结构，判定理由用中文。",
            )
            judge = RecordingJudge(delegate)
            metrics = {"faithfulness": Faithfulness(llm=judge),
                       "factual_correctness": FactualCorrectness(
                           llm=judge, mode="f1", atomicity="high", coverage="high")}
            with (directory / "scores.jsonl").open("x", encoding="utf-8") as output:
                for row in rows:
                    scored = await score_one(row, metrics, judge, api_key)
                    scored_rows.append(scored)
                    output.write(json.dumps(scored, ensure_ascii=False) + "\n")
                    output.flush()
                    metadata["completed_records"] += 1
                    values = ", ".join(f"{name}={item['score']} ({item['status']})"
                                       for name, item in scored["metrics"].items())
                    print(f"[{len(scored_rows)}/{len(rows)}] {row['case_id']} {scored['status']} {values}")
        metadata["status"] = "completed_with_errors" if any(
            row["status"] == "error" for row in scored_rows) else "completed"
    except (KeyboardInterrupt, asyncio.CancelledError):
        metadata["status"] = "interrupted"
        print("已中断，已完成评分保留。")
    except BaseException:
        metadata["status"] = "failed"
        raise
    finally:
        summary = summarize(scored_rows)
        write_json(directory / "summary.json", summary)
        write_json(directory / "scoring_run.json", metadata)
        cache.cache.close()
        lines = ["Ragas生成评分摘要（本轮不是三种检索策略对比）"]
        for name, item in summary["metrics"].items():
            lines.append(f"{name}: 均分={item['mean_score']}, 有效题={item['scored_cases']}, "
                         f"评分失败={item['failed_cases']}, 不适用={item['skipped_cases']}")
        lines.append("人工检查题：" + ", ".join(summary["manual_review_case_ids"]))
        lines.append("失败题：" + ", ".join(summary["failed_case_ids"]))
        (directory / "summary.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
        print("\n".join(lines))
    return 0 if metadata["status"] == "completed" else 1


def main():
    parser = argparse.ArgumentParser(description="用 Ragas 给已有完整问答评分，不调用问答接口")
    parser.add_argument("--results", type=Path, help="生成采集的 results.jsonl，默认取最新一次")
    parser.add_argument("--limit", type=int, default=0, help="只处理前几道题，0为本采集文件全部题")
    parser.add_argument("--model", default=os.getenv("HUIANSWER_EVAL_MODEL") or
                        os.getenv("HUIANSWER_CHAT_MODEL", "deepseek-ai/DeepSeek-V4-Flash"))
    parser.add_argument("--base-url", default=os.getenv("HUIANSWER_EVAL_BASE_URL") or
                        os.getenv("HUIANSWER_AI_URL", "https://api.siliconflow.cn"))
    parser.add_argument("--timeout", type=float, default=90)
    parser.add_argument("--dry-run", action="store_true", help="校验采集文件和依赖，不调用模型")
    args = parser.parse_args()
    if args.limit < 0 or not args.timeout > 0:
        parser.error("limit 不能为负数，timeout 必须大于0")
    args.base_url = args.base_url.rstrip("/")
    if not args.base_url.endswith("/v1"):
        args.base_url += "/v1"
    source = (args.results or latest_results()).resolve()
    rows = load_rows(source, args.limit)
    count = sum(row["status"] == "success" and row.get("generation_scorable") for row in rows)
    print(f"采集文件：{source}\n将处理 {len(rows)} 条记录，其中 {count} 道知识问答可评分。")
    source_run = source.parent / "run.json"
    if source_run.exists():
        status = json.loads(source_run.read_text(encoding="utf-8"))["status"]
        if status != "completed":
            print(f"注意：采集状态为 {status}，本次仅处理文件中已保存的记录。")
    if args.dry_run:
        print("数据及依赖校验通过，未调用评分模型。")
        return 0
    api_key = ""
    if count:
        api_key = (os.getenv("HUIANSWER_EVAL_API_KEY") or os.getenv("HUIANSWER_AI_API_KEY") or
                   getpass.getpass("粘贴模型API key（不是登录token，输入不显示）：")).strip()
        if not api_key:
            raise ValueError("模型API key不能为空")
    try:
        return asyncio.run(run_scoring(args, rows, source, api_key))
    except Exception as error:
        message = str(error).replace(api_key, "[REDACTED]") if api_key else str(error)
        raise RuntimeError(message) from None


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    try:
        sys.exit(main())
    except Exception as error:
        print(f"评分未完成：{error}", file=sys.stderr)
        sys.exit(1)
