"""只整理已采集的链路信息和延迟，不调用业务或评分模型。"""

import argparse
import json
import math
from pathlib import Path
import statistics
import sys


def percentile(values, quantile):
    ordered = sorted(values)
    position = (len(ordered) - 1) * quantile
    lower, upper = math.floor(position), math.ceil(position)
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (position - lower)


def intent_check(row):
    routes = (row.get("pipeline_trace") or {}).get("routes", [])
    allowed = row.get("acceptable_intents", [])
    if not routes or not allowed or any(route.get("fallback") for route in routes):
        return None
    return all(route.get("intent") in allowed for route in routes)


def show_ms(value):
    return f"{value:.0f} ms" if isinstance(value, (int, float)) else "未记录"


def cell(value):
    return str(value).replace("|", "\\|").replace("\n", " ").replace("\r", "")


def write_pipeline_report(directory, rows):
    successful = [row for row in rows if row.get("status") == "success"]
    latency = {}
    for label, group in (("全部成功题", successful),
                         ("知识问答题", [row for row in successful if row.get("generation_scorable")])):
        values = [row["first_token_ms"] for row in group if isinstance(row.get("first_token_ms"), (int, float))]
        latency[label] = {"count": len(values), "missing": len(group) - len(values),
                          "mean_ms": statistics.mean(values) if values else None,
                          "p50_ms": percentile(values, 0.5) if values else None,
                          "p95_ms": percentile(values, 0.95) if values else None}
    checked = [intent_check(row) for row in successful]
    checks = [result for result in checked if result is not None]
    summary = {"version": "generation-trace-v1", "latency": latency,
               "intent_allowed_set_check": {"checked_cases": len(checks), "matched_cases": sum(checks),
                   "match_rate": sum(checks) / len(checks) if checks else None,
                   "unscored_cases": len(checked) - len(checks)}}
    lines = ["# 问答链路与首字响应报告", "",
             "首字响应：客户端发出本轮请求到收到第一个非空白正文 delta；不计 meta、心跳、评测事件或推理内容。", "",
             "意图只粗查各子问题是否落在题集允许的集合，不代表子问题拆分或选库一定正确；异常降级单列，不当成成功分类。", "",
             "改写和拆分请人工核对：主体、数字、限制是否保留，是否把问题改成答案。知识库名称仅对照展示，不强制唯一正确路由。", "",
             "## 首字时间汇总", "", "| 范围 | 有效题数 | 平均 | P50 | P95 | 未记录 |", "| --- | ---: | ---: | ---: | ---: | ---: |"]
    for label, stats in latency.items():
        lines.append(f"| {label} | {stats['count']} | {show_ms(stats['mean_ms'])} | {show_ms(stats['p50_ms'])} | {show_ms(stats['p95_ms'])} | {stats['missing']} |")
    lines.extend(["", "样本少时 P95 仅作初步观察；失败或无首字的请求不以 0 ms 计入均值。", "",
                  f"允许意图集合检查：{sum(checks)} / {len(checks)} 题命中；{len(checked) - len(checks)} 题未计入。", "",
                  "## 逐题概览", "", "| 题号 | 问题 | 首字时间 | 意图粗查 | 最终片段数 |", "| --- | --- | ---: | --- | ---: |"])
    for row in rows:
        verdict = intent_check(row)
        label = {True: "命中允许集合", False: "不符预期", None: "未计分/缺记录"}[verdict]
        lines.append(f"| [{row['case_id']}](#{row['case_id'].lower()}) | {cell(row['user_input'])} | {show_ms(row.get('first_token_ms'))} | {label} | {len(row.get('retrieved_contexts', []))} |")
    for row in rows:
        trace = row.get("pipeline_trace") or {}
        rewrite = trace.get("rewrite", {})
        stats = row.get("generation_stats") or {}
        lines.extend(["", f"## {row['case_id']}", "", "**原问题**", "", row["user_input"], ""])
        if row.get("warmup_history"):
            lines.extend(["<details>", "<summary>展开本题实际前置对话</summary>", ""])
            for message in row["warmup_history"]:
                lines.extend([f"**{message['role']}**", "", message["content"], ""])
            lines.extend(["</details>", ""])
        if not trace:
            lines.extend(["本题没有过程事件，无法从旧结果反推改写和路由。", ""])
        else:
            lines.extend(["**完整改写**", "", rewrite.get("rewrittenQuestion") or "未记录", "",
                          f"改写异常降级：{'是' if rewrite.get('fallback') else '否'}；异常类型：{rewrite.get('errorType') or '无'}。", "",
                          "**子问题与实际路由**", "",
                          "| 序号 | 子问题 | 意图 | 知识库 | 路由降级 | 路由耗时 | 检索状态 | 检索耗时 | 候选数 |",
                          "| --- | --- | --- | --- | --- | ---: | --- | ---: | ---: |"])
            errors = []
            for route in trace.get("routes", []):
                names = "、".join(route.get("kb_names") or [])
                if route.get("intent") == "NO_MATCH":
                    names = "全库（NO_MATCH）"
                elif not names:
                    names = "不检索" if route.get("search_status") == "skipped" else "未记录"
                lines.append(f"| {route.get('index')} | {cell(route.get('question', ''))} | {route.get('intent')} | {cell(names)} | {'是' if route.get('fallback') else '否'} | {show_ms(route.get('route_ms'))} | {route.get('search_status')} | {show_ms(route.get('search_ms'))} | {len(route.get('candidate_chunk_ids', []))} |")
                if route.get("route_error_type") or route.get("search_error_type"):
                    errors.append(f"子问题 {route.get('index')} 异常：路由={route.get('route_error_type') or '无'}；检索={route.get('search_error_type') or '无'}。")
            lines.extend(["", *errors, "", "允许的意图：" + "、".join(row.get("acceptable_intents", [])) + "。", "",
                          "预期涉及知识库：" + ("、".join(row.get("expected_kb_names", [])) or "未限定") + "。", "",
                          "**阶段耗时**", ""])
            for name, value in trace.get("stage_ms", {}).items():
                lines.append(f"- {name}：{show_ms(value)}")
            lines.extend(["", "子问题阶段为并行任务的墙钟耗时，包含排队等开销；不能把各子问题耗时相加当作用户等待时间。", "",
                          f"配置模型：{trace.get('configured_model')}；响应模型：{stats.get('answer_model') or '未记录'}；实际回答温度：{trace.get('answer_temperature')}。", ""])
        lines.extend([f"端到端首字：{show_ms(row.get('first_token_ms'))}；回答模型等待首段正文：{show_ms(stats.get('model_first_delta_ms'))}；本轮总耗时：{show_ms(row.get('http_elapsed_ms'))}。", "",
                      f"本次最终参考片段：{len(row.get('retrieved_contexts', []))} 段。", "",
                      "**实际回答**", "", row.get("response") or "（空）", ""])
        if row.get("error"):
            lines.extend(["采集错误：" + row["error"], ""])
    (directory / "pipeline-summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
    output = directory / "pipeline-report.md"
    output.write_text("\n".join(lines)+"\n", encoding="utf-8")
    print(f"过程报告：{output}")


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--results", required=True, type=Path)
    args = parser.parse_args()
    source = args.results.resolve()
    rows = [json.loads(line) for line in source.read_text(encoding="utf-8-sig").splitlines() if line.strip()]
    write_pipeline_report(source.parent, rows)
