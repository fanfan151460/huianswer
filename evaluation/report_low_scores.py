"""离线整理完整问答评测：按主要指标各自的均分筛选，不重新请求模型。"""

import argparse
import hashlib
import json
import math
from pathlib import Path
import statistics
import sys


LABELS = {
    "context_recall": "检索召回",
    "context_precision": "检索精度",
    "faithfulness": "忠实度",
    "answer_point_coverage": "要点覆盖",
    "factual_correctness": "事实 F1（辅助）",
}
PRIMARY = tuple(name for name in LABELS if name != "factual_correctness")


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def read_rows(path):
    return [json.loads(line) for line in path.read_text(encoding="utf-8-sig").splitlines() if line.strip()]


def valid_score(item):
    score = item.get("score")
    return (item.get("status") == "success" and isinstance(score, (int, float))
            and not isinstance(score, bool) and math.isfinite(score) and 0 <= score <= 1)


def latest_scores():
    paths = []
    for path in Path(__file__).resolve().parent.glob("*/runs/generation/*/scores/*/scores.jsonl"):
        metadata = path.parent / "scoring_run.json"
        if metadata.exists() and read_json(metadata).get("status") in {"completed", "completed_with_errors"}:
            paths.append(path)
    if not paths:
        raise ValueError("没有已完成的完整问答评分，请用 --scores 指定 scores.jsonl")
    return max(paths, key=lambda path: (path.parent.name, str(path)))


def low_metrics(row, means, names=PRIMARY):
    return [name for name in names if means.get(name) is not None
            and valid_score(row.get("metrics", {}).get(name, {}))
            and row["metrics"][name]["score"] < means[name]]


def cell(value):
    return str(value).replace("|", "\\|").replace("\n", " ").replace("\r", "")


def formatted_score(row, name, means):
    item = row.get("metrics", {}).get(name, {})
    if not valid_score(item):
        return "—（" + item.get("status", "无记录") + "）"
    text = f"{item['score']:.4f}"
    return f"**{text} ↓**" if name in low_metrics(row, means) else text


def metric_notes(row, name):
    """只展开主要低分项的负向判定；保留原判词，不替评委下结论。"""
    item = row["metrics"][name]
    notes = []
    if item.get("note"):
        notes.extend([item["note"], ""])
    if name == "answer_point_coverage":
        facts = [(point["point"], point.get("reason", ""))
                 for point in item.get("judgments", []) if point.get("verdict") == 0]
    elif name == "context_precision":
        verdicts = [decision["result"] for decision in item.get("decisions", [])
                    if decision.get("type") == "ContextPrecisionOutput"]
        ids = row.get("chunk_ids", [])
        facts = [(f"第 {index + 1} 段，chunkId={ids[index] if index < len(ids) else '未知'}",
                  verdict.get("reason", ""))
                 for index, verdict in enumerate(verdicts) if verdict.get("verdict") == 0]
    else:
        facts = []
        for decision in item.get("decisions", []):
            result = decision.get("result", {})
            if name == "context_recall":
                facts.extend((fact["statement"], fact.get("reason", ""))
                             for fact in result.get("classifications", []) if fact.get("attributed") == 0)
            elif decision.get("type") == "NLIStatementOutput":
                facts.extend((fact["statement"], fact.get("reason", ""))
                             for fact in result.get("statements", [])
                             if isinstance(fact, dict) and fact.get("verdict") == 0)
    for statement, reason in facts:
        notes.extend(["- " + statement.replace("\n", " "),
                      "  - 评委理由：" + reason.replace("\n", " ")])
    return notes or ["未记录具体的负向判定，请结合实际参考片段人工核对。"]


def render_report(rows, summary, metadata, raw_rows, source):
    means = {name: item.get("mean_score") for name, item in summary["metrics"].items()}
    selected = [row for row in rows if low_metrics(row, means)]
    selected.sort(key=lambda row: (-len(low_metrics(row, means)), row["case_id"]))
    auxiliary_only = [row["case_id"] for row in rows if not low_metrics(row, means)
                      and low_metrics(row, means, ("factual_correctness",))]
    lines = ["# 主要指标低于均分的问答", "",
             f"评分批次：`{source.parent.name}`；采集批次：`{source.parents[2].name}`。", "",
             f"口径：{metadata.get('protocol_version', '未记录')}；参考答案：{metadata.get('reference_revision', '未记录')}。", "",
             f"筛选出 **{len(selected)} 题**。四项主要指标中，任意一项严格低于本轮该指标均分即入选；相等不选。", "",
             "事实 F1 仅辅助展示，不触发筛选。按主要低分项数量降序排列，同数量按题号排列，不合成总分。", "",
             "低于均分不等于答错；下文理由均来自原评委，仍需核对资料。检索精度采用原评分口径，不是相关片段数除以总片段数。", "",
             f"评分状态：`{metadata.get('status', '未记录')}`。评分失败的项不按零分处理，也不参与该项均分。", "",
             "## 本轮均分", "", "| 指标 | 均分 | 有效题数 | 评分失败 | 跳过 |", "| --- | ---: | ---: | ---: | ---: |"]
    for name, label in LABELS.items():
        item = summary["metrics"].get(name, {})
        mean = item.get("mean_score")
        lines.append(f"| {label} | {f'{mean:.4f}' if mean is not None else '—'} | {item.get('scored_cases', 0)} | {item.get('failed_cases', 0)} | {item.get('skipped_cases', 0)} |")
    failed = [(row["case_id"], name) for row in rows
              for name, item in row.get("metrics", {}).items() if item.get("status") == "error"]
    if failed:
        lines.extend(["", "## 评分失败项（不是回答判错）", ""])
        lines.extend(f"- {case_id}：{LABELS.get(name, name)}未完成评分；需单独复查或补评。" for case_id, name in failed)
    lines.extend(["", "## 入选题目", "",
                  "| 题号 | 问题 | 低于均分的主要指标 |", "| --- | --- | --- |"])
    for row in selected:
        names = "、".join(LABELS[name] for name in low_metrics(row, means))
        lines.append(f"| [{row['case_id']}](#{row['case_id'].lower()}) | {cell(row['user_input'])} | {names} |")
    if not selected:
        lines.extend(["", "没有主要指标低于均分的题目。"])
    for row in selected:
        lines.extend(["", f"## {row['case_id']}", "", "### 问题", "", row["user_input"], ""])
        if row.get("warmup_history"):
            lines.extend(["### 本题实际前置对话", ""])
            for message in row["warmup_history"]:
                role = "用户" if message["role"] == "user" else "助手"
                lines.extend([f"**{role}**", "", message["content"], ""])
        lines.extend(["### 分数", "", "| 指标 | 本题 | 本轮均分 |", "| --- | ---: | ---: |"])
        for name, label in LABELS.items():
            mean = means.get(name)
            lines.append(f"| {label} | {formatted_score(row, name, means)} | {f'{mean:.4f}' if mean is not None else '—'} |")
        lines.extend(["", "### 实际回答", "", row.get("response") or "（空回答）", "",
                      "### 本次评分参考答案", "", row.get("reference") or "（未记录）", "",
                      "### 必要要点", ""])
        judgments = row.get("metrics", {}).get("answer_point_coverage", {}).get("judgments", [])
        for index, point in enumerate(row.get("expected_answer_points", []), 1):
            judgment = next((item for item in judgments if item.get("index") == index), {})
            label = {1: "已覆盖", 0: "未覆盖"}.get(judgment.get("verdict"), "未判定")
            lines.append(f"- **{label}**：{point}")
        lines.extend(["", "### 主要低分项：评委的原始判定", ""])
        for name in low_metrics(row, means):
            lines.extend([f"#### {LABELS[name]}", "", *metric_notes(row, name), ""])
        lines.extend(["<details>", "<summary>展开本次实际参考片段（保留原顺序）</summary>", ""])
        chunks = raw_rows.get(row["case_id"], {}).get("chunks", [])
        ids = row.get("chunk_ids", [])
        for index, content in enumerate(row.get("retrieved_contexts", [])):
            chunk = chunks[index] if index < len(chunks) else {}
            chunk_id = ids[index] if index < len(ids) else "未知"
            lines.extend([f"#### 片段 {index + 1}：{chunk.get('docName', '文档名未记录')}（chunkId={chunk_id}）", "",
                          str(content), ""])
        if not row.get("retrieved_contexts"):
            lines.extend(["本次没有检索到参考片段。", ""])
        lines.extend(["</details>", ""])
    manual = summary.get("manual_review_case_ids", [])
    lines.extend(["## 其他检查项", "",
                  "- 仅辅助事实 F1 低于均分：" + ("、".join(auxiliary_only) or "无") + "。",
                  "- 原本需要人工检查的题：" + ("、".join(manual) or "无") + "。",
                  "- 原汇总记录的失败题：" + ("、".join(summary.get("failed_case_ids", [])) or "无") + "。", "",
                  "报告只整理已有本地文件，没有重新调用问答或评分模型，也没有修改原始结果。", ""])
    return "\n".join(lines), selected


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scores", type=Path, help="scores.jsonl 或其目录；默认找最近已完成的完整问答评分")
    parser.add_argument("--output", type=Path, help="输出 Markdown 路径；默认与评分文件同目录")
    args = parser.parse_args()
    source = (args.scores or latest_scores()).resolve()
    if source.is_dir():
        source /= "scores.jsonl"
    rows = read_rows(source)
    summary = read_json(source.parent / "summary.json")
    metadata = read_json(source.parent / "scoring_run.json")
    if metadata.get("input_mode") != "end_to_end_chat":
        raise ValueError("请指定完整问答评分，不能使用独立检索结果")
    if len({row['case_id'] for row in rows}) != len(rows):
        raise ValueError("评分文件有重复题号")
    for name in PRIMARY:
        values = [row["metrics"][name]["score"] for row in rows
                  if valid_score(row.get("metrics", {}).get(name, {}))]
        mean = summary["metrics"][name]["mean_score"]
        if values and (mean is None or not math.isclose(statistics.mean(values), mean, abs_tol=1e-12)):
            raise ValueError(f"{name} 的均分与当前评分文件不一致，请检查是否混用了批次")
    raw_path = Path(metadata["source"])
    if not raw_path.is_file():
        raw_path = source.parents[2] / "results.jsonl"
    raw_rows = {}
    if raw_path.is_file():
        expected_hash = metadata.get("source_sha256")
        if expected_hash and hashlib.sha256(raw_path.read_bytes()).hexdigest() != expected_hash:
            raise ValueError("采集文件的哈希与评分批次不一致")
        raw_rows = {row["case_id"]: row for row in read_rows(raw_path)}
    output = args.output.resolve() if args.output else source.parent / "low-score-review.md"
    if output in {source, source.parent / "summary.json", source.parent / "scoring_run.json", raw_path}:
        raise ValueError("输出不能覆盖原始采集或评分文件")
    text, selected = render_report(rows, summary, metadata, raw_rows, source)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(text, encoding="utf-8")
    print(f"评分文件：{source}；状态：{metadata.get('status')}")
    print(f"主要指标低于均分：{len(selected)} 题，" + "、".join(row["case_id"] for row in selected))
    print(f"报告：{output}")
    return 0


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    try:
        sys.exit(main())
    except (OSError, ValueError, KeyError) as error:
        print(f"整理失败：{error}", file=sys.stderr)
        sys.exit(1)
