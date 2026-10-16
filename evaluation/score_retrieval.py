"""用 Ragas 给采集结果评分；不重新执行 Java 检索。"""

import argparse
import asyncio
from collections import defaultdict
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

os.environ["RAGAS_DO_NOT_TRACK"] = "true"

from openai import AsyncOpenAI
from ragas.cache import DiskCacheBackend
from ragas.llms import llm_factory
from ragas.llms.base import InstructorBaseRagasLLM
from ragas.metrics.collections import ContextPrecision, ContextRecall


class RecordingJudge(InstructorBaseRagasLLM):
    """只记录评委的结构化判定，实际打分算法仍使用 Ragas。"""

    def __init__(self, delegate):
        self.delegate = delegate
        self.decisions = []

    def remember(self, response_model, result):
        data = result.model_dump()
        labels = [data["verdict"]] if "verdict" in data else [
            item["attributed"] for item in data.get("classifications", [])
        ]
        if any(label not in (0, 1) for label in labels):
            raise ValueError("评委的相关性标记必须为0或1")
        self.decisions.append({"type": response_model.__name__, "result": data})
        return result

    def generate(self, prompt, response_model):
        return self.remember(response_model, self.delegate.generate(prompt, response_model))

    async def agenerate(self, prompt, response_model):
        result = await self.delegate.agenerate(prompt, response_model)
        return self.remember(response_model, result)


def write_json(path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def latest_results():
    runs = Path(__file__).resolve().parent / "xinglan-shop-v1" / "runs"
    paths = sorted(runs.glob("*/results.jsonl"))
    if not paths:
        raise ValueError("没有找到采集结果，请先运行 collect_retrieval.py")
    return paths[-1]


def load_rows(path, limit):
    rows = [json.loads(line) for line in path.read_text(encoding="utf-8-sig").splitlines() if line.strip()]
    if not rows or len({(row["case_id"], row["strategy"]) for row in rows}) != len(rows):
        raise ValueError("结果为空或存在重复的题号、策略组合")
    if any(row["status"] != "success" for row in rows):
        raise ValueError("采集文件含失败请求，请先排查或重新采集，不把调用失败当作检索0分")
    case_ids = list(dict.fromkeys(row["case_id"] for row in rows))
    selected = set(case_ids[:limit] if limit else case_ids)
    rows = [row for row in rows if row["case_id"] in selected]
    for row in rows:
        if not row.get("user_input") or not row.get("reference"):
            raise ValueError("每条结果必须有 user_input 和 reference")
        if not isinstance(row.get("retrieved_contexts"), list):
            raise ValueError("retrieved_contexts 必须是数组")
    return rows


async def score_one(row, metrics, judge, api_key):
    """逐个指标评分，保存理由；评委出错保留错误，不记为检索0分。"""
    out = {key: row[key] for key in (
        "case_id", "strategy", "user_input", "reference", "retrieved_contexts",
        "http_elapsed_ms", "server_elapsed_ms",
    )}
    out["chunk_ids"] = [c["chunkId"] for c in row["chunks"]]
    out["metrics"] = {}
    for name, metric in metrics.items():
        judge.decisions.clear()
        start = time.perf_counter()
        result = {"status": "error", "score": None, "decisions": [], "error": None}
        try:
            if not row["retrieved_contexts"]:
                # 对有答案的题，空召回没有覆盖任何依据；不花一次模型请求确认空列表。
                result.update(status="success", score=0.0)
                result["decisions"] = [{"reason": "有参考答案，但返回片段为空"}]
            else:
                scored = await metric.ascore(
                    user_input=row["user_input"],
                    reference=row["reference"],
                    retrieved_contexts=row["retrieved_contexts"],
                )
                value = float(scored.value)
                if not math.isfinite(value) or not 0 <= value <= 1:
                    raise ValueError("评委没有返回有效的0至1分数")
                result.update(status="success", score=value)
        except Exception as error:
            result["error"] = f"{type(error).__name__}: {error}".replace(api_key, "[REDACTED]")
        if judge.decisions:
            result["decisions"] = list(judge.decisions)
        result["scoring_ms"] = round((time.perf_counter() - start) * 1000, 3)
        out["metrics"][name] = result
    out["status"] = "success" if all(m["status"] == "success" for m in out["metrics"].values()) else "error"
    return out


def summarize(rows, strategies):
    """只在所有策略都成功评分的共同题目上比较，防止各组漏题造成偏差。"""
    successful_cases = {
        strategy: {row["case_id"] for row in rows if row["strategy"] == strategy and row["status"] == "success"}
        for strategy in strategies
    }
    common = set.intersection(*successful_cases.values()) if successful_cases else set()
    groups = defaultdict(list)
    for row in rows:
        groups[row["strategy"]].append(row)
    comparisons = []
    for strategy in strategies:
        group = groups[strategy]
        paired = [row for row in group if row["case_id"] in common and row["status"] == "success"]
        averages = {
            metric: statistics.mean(row["metrics"][metric]["score"] for row in paired) if paired else None
            for metric in ("context_recall", "context_precision")
        }
        comparisons.append({
            "strategy": strategy,
            "scored_rows": len(group),
            "failed_rows": sum(row["status"] != "success" for row in group),
            "paired_cases": len(paired),
            **averages,
            "mean_retrieval_http_ms": statistics.mean(row["http_elapsed_ms"] for row in paired) if paired else None,
        })
    return {"common_case_ids": sorted(common), "common_case_count": len(common), "strategies": comparisons}


async def run_scoring(args, rows, source, api_key):
    # Windows 注册表有时把同一HTTP代理的HTTPS地址标成https://。
    # 仅为本进程使用其HTTP CONNECT入口，保留证书验证。
    proxies = urllib.request.getproxies()
    if os.name == "nt" and not os.getenv("HTTPS_PROXY") and not os.getenv("https_proxy"):
        http_proxy, https_proxy = proxies.get("http", ""), proxies.get("https", "")
        if http_proxy and https_proxy and urlsplit(http_proxy).netloc == urlsplit(https_proxy).netloc:
            os.environ["HTTPS_PROXY"] = http_proxy

    score_id = datetime.now().strftime("%Y%m%d-%H%M%S-%f")
    directory = source.parent / "scores" / score_id
    directory.mkdir(parents=True, exist_ok=False)
    versions = {name: importlib.metadata.version(name) for name in (
        "ragas", "instructor", "openai", "langchain-openai", "langchain-community", "langchain", "langchain-core",
    )}
    metadata = {
        "source": str(source), "source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
        "judge_model": args.model, "judge_base_url": args.base_url,
        "temperature": 0, "max_tokens": 4096, "versions": versions,
        "planned_records": len(rows), "completed_records": 0, "status": "running",
        "metrics": ["context_recall", "context_precision"], "input_mode": "standalone_retrieval_query",
    }
    write_json(directory / "scoring_run.json", metadata)
    cache = DiskCacheBackend(cache_dir=str(directory / "judge-cache"))
    scored_rows = []
    strategies = list(dict.fromkeys(row["strategy"] for row in rows))
    print(f"评分结果目录：{directory}")
    try:
        async with AsyncOpenAI(api_key=api_key, base_url=args.base_url,
                               timeout=args.timeout, max_retries=1) as client:
            delegate = llm_factory(
                args.model, client=client, cache=cache, temperature=0, max_tokens=4096,
                max_retries=1, extra_body={"enable_thinking": False},
                system_prompt="你是严格的检索评测员。只依据给定片段判断其是否支持参考答案，不用外部知识补齐。"
                              "按照评测提示要求返回JSON，判定理由用中文。",
            )
            judge = RecordingJudge(delegate)
            metrics = {"context_recall": ContextRecall(llm=judge),
                       "context_precision": ContextPrecision(llm=judge)}
            with (directory / "scores.jsonl").open("x", encoding="utf-8") as output:
                for row in rows:
                    scored = await score_one(row, metrics, judge, api_key)
                    scored_rows.append(scored)
                    output.write(json.dumps(scored, ensure_ascii=False) + "\n")
                    output.flush()
                    metadata["completed_records"] += 1
                    values = ", ".join(f"{name}={value['score']}" for name, value in scored["metrics"].items())
                    print(f"[{len(scored_rows)}/{len(rows)}] {row['case_id']} {row['strategy']} {values}")
        metadata["status"] = "completed" if all(row["status"] == "success" for row in scored_rows) else "completed_with_errors"
    except (KeyboardInterrupt, asyncio.CancelledError):
        metadata["status"] = "interrupted"
        print("已中断，已完成的评分保留在文件中。")
    except BaseException:
        metadata["status"] = "failed"
        raise
    finally:
        summary = summarize(scored_rows, strategies)
        write_json(directory / "summary.json", summary)
        write_json(directory / "scoring_run.json", metadata)
        cache.cache.close()
        lines = ["Ragas检索评分摘要", f"可公平比较的共同题目：{summary['common_case_count']}", ""]
        for item in summary["strategies"]:
            lines.append(f"{item['strategy']}: Recall={item['context_recall']}, "
                         f"Precision={item['context_precision']}, 平均检索HTTP毫秒={item['mean_retrieval_http_ms']}, "
                         f"评分失败={item['failed_rows']}")
        (directory / "summary.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
        print("\n".join(lines))
    return 0 if metadata["status"] == "completed" else 1


def main():
    parser = argparse.ArgumentParser(description="用Ragas给已有检索结果评分，不重新执行检索")
    parser.add_argument("--results", type=Path, help="results.jsonl路径，默认选最新一次采集")
    parser.add_argument("--limit", type=int, default=0, help="只评前几道题的全部策略，0表示全部题")
    parser.add_argument("--model", default=os.getenv("HUIANSWER_EVAL_MODEL") or os.getenv(
        "HUIANSWER_CHAT_MODEL", "deepseek-ai/DeepSeek-V4-Flash"))
    parser.add_argument("--base-url", default=os.getenv("HUIANSWER_EVAL_BASE_URL") or
                        os.getenv("HUIANSWER_AI_URL", "https://api.siliconflow.cn"))
    parser.add_argument("--timeout", type=float, default=90)
    parser.add_argument("--dry-run", action="store_true", help="只校验数据和依赖，不发送模型请求")
    args = parser.parse_args()
    if args.limit < 0 or not args.timeout > 0:
        parser.error("limit不能为负数，timeout必须大于0")
    args.base_url = args.base_url.rstrip("/")
    if not args.base_url.endswith("/v1"):
        args.base_url += "/v1"
    source = (args.results or latest_results()).resolve()
    rows = load_rows(source, args.limit)
    print(f"采集文件：{source}\n将评分 {len(rows)} 条记录，评委模型：{args.model}")
    print("ContextPrecision会逐段请求评委，缓存可复用相同判定；评分请求数不等于记录条数。")
    if args.dry_run:
        print("数据及依赖校验通过，未发送模型请求。")
        return 0
    api_key = (os.getenv("HUIANSWER_EVAL_API_KEY") or os.getenv("HUIANSWER_AI_API_KEY")
               or getpass.getpass("粘贴模型API key（不是登录token，输入不显示）：")).strip()
    if not api_key:
        raise ValueError("模型API key不能为空")
    try:
        return asyncio.run(run_scoring(args, rows, source, api_key))
    except Exception as error:
        raise RuntimeError(str(error).replace(api_key, "[REDACTED]")) from None


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    try:
        sys.exit(main())
    except Exception as error:
        print(f"评分未完成：{error}", file=sys.stderr)
        sys.exit(1)
