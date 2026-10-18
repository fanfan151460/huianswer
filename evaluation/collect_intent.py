"""复用问答采集中的会话，调用意图评测接口并按题集标注比较；不创建历史。"""

import argparse
from collections import Counter
from datetime import datetime
import getpass
import hashlib
import json
import os
from pathlib import Path
import statistics
import sys
import time
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

from collect_retrieval import write_json


INTENT_LABELS = {
    "CHAT": "无需查询资料的寒暄、感谢或普通交流。",
    "KNOWLEDGE": "需要查询资料，并能确定相关知识库。",
    "CLARIFY": "主体或检索范围不明确，需要用户补充信息。",
    "NO_MATCH": "需求明确，但候选目录中没有合适的知识库或模型意图路由失败",
}
INTENT_NAMES = {label: name for name, label in INTENT_LABELS.items()}


def load_rows(source, limit=0, case_ids=None, cases_path=None):
    rows = [json.loads(line) for line in source.read_text(encoding="utf-8-sig").splitlines()
            if line.strip()]
    if cases_path:
        # 新题只替换当前输入与标注，沿用源题会话和原题的历史截断位置。
        if len({row.get("case_id") for row in rows}) != len(rows):
            raise ValueError("来源采集文件题号重复")
        originals = {row["case_id"]: row for row in rows}
        cases = [json.loads(line) for line in cases_path.read_text(encoding="utf-8-sig").splitlines()
                 if line.strip()]
        rows = []
        for case in cases:
            original = originals.get(case.get("source_case_id"))
            if not original or original.get("status") != "success":
                raise ValueError(f"{case.get('id')} 对应的来源题不存在或未采集成功")
            if not case.get("id") or not isinstance(case.get("rationale"), str) or not case["rationale"].strip():
                raise ValueError("新题必须填写 id 和预期分类理由 rationale")
            rows.append({**original, "case_id": case["id"], "user_input": case.get("question"),
                         "acceptable_intents": case.get("acceptable_intents"),
                         "expected_kb_names": [], "source_case_id": original["case_id"],
                         "history_before_question": original["user_input"],
                         "label_reason": case["rationale"]})
    if case_ids:
        missing = set(case_ids) - {row.get("case_id") for row in rows}
        if missing:
            raise ValueError("采集文件没有这些题号：" + ", ".join(sorted(missing)))
        rows = [row for row in rows if row.get("case_id") in set(case_ids)]
    rows = [row for row in rows if row.get("status") == "success"]
    if limit:
        rows = rows[:limit]
    if not rows or len({row.get("case_id") for row in rows}) != len(rows):
        raise ValueError("没有成功的问答记录，或题号重复")
    for row in rows:
        cid, expected = row.get("conversation_id"), row.get("acceptable_intents")
        if not isinstance(cid, int) or isinstance(cid, bool) or cid <= 0:
            raise ValueError(f"{row['case_id']} 缺少有效的 conversation_id")
        if not isinstance(row.get("user_input"), str) or not row["user_input"].strip():
            raise ValueError(f"{row['case_id']} 缺少原问题")
        if not expected or any(intent not in INTENT_LABELS for intent in expected):
            raise ValueError(f"{row['case_id']} 缺少有效的 acceptable_intents 标注")
    return rows


def request_intent(base_url, headers, timeout, question, conversation_id, history_before_question=None):
    # 现有 Controller 没有 @RequestBody，按表单字段绑定，不能发送 JSON body。
    fields = {"question": question, "conversationId": conversation_id}
    if history_before_question is not None:
        fields["historyBeforeQuestion"] = history_before_question
    body = urlencode(fields).encode("utf-8")
    request = Request(base_url + "/api/evaluation/intent", data=body, headers=headers)
    with urlopen(request, timeout=timeout) as response:
        payload = json.loads(response.read().decode("utf-8-sig"))
    if isinstance(payload, dict) and "code" in payload:
        if str(payload["code"]) != "0":
            raise RuntimeError(f"业务错误 {payload['code']}：{payload.get('message') or '请求失败'}")
        payload = payload.get("data")
    if not isinstance(payload, list) or not payload:
        raise ValueError("意图接口必须返回非空数组")
    return payload


def collect_one(source, base_url, headers, timeout):
    row = {"case_id": source["case_id"], "question": source["user_input"],
           "conversation_id": source["conversation_id"],
           "expected_intents": source["acceptable_intents"],
           "expected_kb_names": source.get("expected_kb_names", []),
           "source_case_id": source.get("source_case_id", source["case_id"]),
           "history_before_question": source.get("history_before_question", source["user_input"]),
           "warmup_history": source.get("warmup_history", []),
           "label_reason": source.get("label_reason"),
           "status": "error", "correct": None, "error": None, "http_status": None,
           "rewrite": None, "predictions": [], "raw_response": None}
    start = time.perf_counter()
    try:
        payload = request_intent(base_url, headers, timeout, row["question"], row["conversation_id"],
                                 source.get("history_before_question"))
        row["raw_response"] = payload
        rewrite = payload[0].get("rewrite") if isinstance(payload[0], dict) else None
        if not isinstance(rewrite, dict) or rewrite.get("fallback"):
            raise ValueError("改写结果缺失或出现异常降级，本题不按正常分类计分")
        questions = rewrite.get("subQuestions")
        if not isinstance(questions, list) or len(questions) != len(payload):
            raise ValueError("子问题数与意图返回数不一致")
        row["rewrite"] = rewrite
        for index, (question, item) in enumerate(zip(questions, payload)):
            if not isinstance(question, str) or not question.strip() or not isinstance(item, dict):
                raise ValueError("子问题或意图结果格式不正确")
            raw = item.get("intent")
            intent = raw if raw in INTENT_LABELS else INTENT_NAMES.get(raw)
            if intent is None:
                raise ValueError("无法识别意图名称，请检查接口返回值")
            row["predictions"].append({"index": index, "question": question,
                                       "intent": intent, "raw_intent": raw,
                                       "correct": intent in row["expected_intents"]})
        row["correct"] = all(item["correct"] for item in row["predictions"])
        row["status"] = "success"
    except HTTPError as error:
        row["http_status"] = error.code
        row["error"] = f"HTTP {error.code}，请检查接口、登录状态或服务日志"
    except Exception as error:
        row["error"] = f"{type(error).__name__}: {error}"
    row["http_elapsed_ms"] = round((time.perf_counter() - start) * 1000, 3)
    if row["error"]:
        row["error"] = row["error"].replace(headers["token"], "[REDACTED]")
    return row


def cell(value):
    return str(value).replace("|", "\\|").replace("\n", " ").replace("\r", "")


def write_report(directory, rows):
    valid = [row for row in rows if row["status"] == "success"]
    matched = [row for row in valid if row["correct"]]
    summary = {"collected_cases": len(rows), "valid_cases": len(valid),
               "failed_cases": len(rows) - len(valid), "matched_cases": len(matched),
               "mismatched_cases": len(valid) - len(matched),
               "intent_accuracy_on_valid_cases": len(matched) / len(valid) if valid else None,
               "matched_fraction_of_collected_cases": len(matched) / len(rows) if rows else None,
               "mean_http_ms": statistics.mean(row["http_elapsed_ms"] for row in valid) if valid else None,
               "mismatched_case_ids": [row["case_id"] for row in valid if not row["correct"]],
               "failed_case_ids": [row["case_id"] for row in rows if row["status"] != "success"],
               "expected_intent_distribution": dict(Counter(intent for row in rows for intent in row["expected_intents"])),
               "rewrite_backup_case_ids": [row["case_id"] for row in rows if (row.get("rewrite") or {}).get("backupUsed")]}
    per_intent = {}
    for intent in INTENT_LABELS:
        # 单标签题按题计分；一个问题拆成多项时，全部同类才算整题正确。
        selected = [row for row in rows if row["expected_intents"] == [intent]]
        successful = [row for row in selected if row["status"] == "success"]
        correct = sum(bool(row["correct"]) for row in successful)
        per_intent[intent] = {"collected_cases": len(selected), "valid_cases": len(successful),
                              "failed_cases": len(selected) - len(successful), "matched_cases": correct,
                              "accuracy_on_valid_cases": correct / len(successful) if successful else None}
    summary["per_intent"] = per_intent
    write_json(directory / "summary.json", summary)
    rate = f"{len(matched) / len(valid):.1%}" if valid else "未计算"
    lines = ["# 意图识别复测报告", "",
             f"有效响应 {len(valid)}，意图符合预期 {len(matched)}，不符 {len(valid) - len(matched)}，请求失败 {len(rows) - len(valid)}；有效响应正确率 {rate}。", "",
             "复用原有会话；新题按指定的原题定位并截断历史，排除原题及其答案，不新增会话或消息。旧题默认按本题原文定位。", "",
             "预期意图来自人工标注的 acceptable_intents，不会发给模型。按整题允许的意图集合检查每个子问题，全部符合才算本题符合；不评价具体知识库选择、改写或拆分质量。", "",
             "请求异常和模型降级单列为失败，不当成分类错误或正确；接口耗时包含读取历史、改写和全部子问题路由。", "",
             "若全部标注为 KNOWLEDGE，本结果只说明业务问题被误分流的情况，不能代表四分类总体准确率。", "",
             "## 按预期意图统计", "",
             "| 预期意图 | 采集题数 | 有效响应 | 正确题数 | 请求失败 | 有效响应正确率 |",
             "| --- | ---: | ---: | ---: | ---: | ---: |"]
    for intent, counts in per_intent.items():
        value = counts["accuracy_on_valid_cases"]
        rate = f"{value:.1%}" if value is not None else "未计算"
        lines.append(f"| {intent} | {counts['collected_cases']} | {counts['valid_cases']} | {counts['matched_cases']} | {counts['failed_cases']} | {rate} |")
    lines.extend(["", "## 逐题结果", "",
                  "| 题号 | 会话 ID | 预期意图 | 实际意图 | 结果 | 接口耗时 |",
                  "| --- | ---: | --- | --- | --- | --- | ---: |"])
    for row in rows:
        actual = "、".join(item["intent"] for item in row["predictions"]) or "未取得"
        verdict = "失败" if row["status"] != "success" else "正确" if row["correct"] else "不符预期"
        lines.append(f"| {row['case_id']} | {row['conversation_id']} | {'、'.join(row['expected_intents'])} | {actual} | {verdict} | {row['http_elapsed_ms']:.0f} ms |")
    for row in rows:
        rewrite = row["rewrite"] or {}
        lines.extend(["", f"## {row['case_id']}", "",
                      f"来源题：{row.get('source_case_id') or row['case_id']}；会话 ID：{row['conversation_id']}。", "",
                      "**当前测试输入**", "", row["question"], ""])
        if row.get("label_reason"):
            lines.extend(["**预期分类理由**", "", row["label_reason"], ""])
        if row.get("warmup_history"):
            lines.extend(["<details>", "<summary>原采集的前置对话（供核对，实际历史由后端读取）</summary>", ""])
            for message in row["warmup_history"]:
                lines.extend([f"**{message['role']}**", "", message["content"], ""])
            lines.extend(["</details>", ""])
        lines.extend(["**历史截断原题（不包含本题及其答案）**", "",
                      row.get("history_before_question") or row["question"], "",
                      "**完整改写**", "", rewrite.get("rewrittenQuestion") or "未取得", "",
                      f"改写模型：{rewrite.get('model') or '未记录'}；调用备用模型：{'是' if rewrite.get('backupUsed') else '否/未记录'}。", ""])
        if rewrite.get("primaryErrorType"):
            lines.extend(["主模型失败：" + rewrite["primaryErrorType"] + "；" + (rewrite.get("primaryErrorMessage") or "未记录"), ""])
        lines.extend(["| 序号 | 子问题 | 实际意图 | 是否符合预期 |", "| ---: | --- | --- | --- |"])
        for item in row["predictions"]:
            lines.append(f"| {item['index']} | {cell(item['question'])} | {item['intent']} | {'是' if item['correct'] else '否'} |")
        if row["error"]:
            lines.extend(["", "采集错误：" + row["error"]])
    output = directory / "report.md"
    output.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"意图报告：{output}")
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--results", required=True, type=Path, help="已完成问答采集的 results.jsonl")
    parser.add_argument("--cases", type=Path, help="可选的新题 JSONL；按 source_case_id 复用来源会话")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--username", default=os.getenv("HUIANSWER_EVAL_USERNAME", "admin"))
    parser.add_argument("--limit", type=int, default=0, help="0 表示全部成功题")
    parser.add_argument("--case-ids", nargs="+")
    parser.add_argument("--timeout", type=float, default=180)
    parser.add_argument("--output-dir", type=Path)
    parser.add_argument("--dry-run", action="store_true", help="只核对输入，不访问服务")
    args = parser.parse_args()
    if args.limit < 0 or not args.timeout > 0:
        parser.error("limit 不能为负，timeout 必须大于 0")
    source = args.results.resolve()
    cases_path = args.cases.resolve() if args.cases else None
    rows = load_rows(source, args.limit, args.case_ids, cases_path)
    distribution = Counter(intent for row in rows for intent in row["acceptable_intents"])
    print(f"来源：{source}")
    if cases_path:
        print(f"新题集：{cases_path}")
    print(f"题目 {len(rows)} 个；复用 {len({row['conversation_id'] for row in rows})} 个会话；预期意图：{dict(distribution)}")
    print("只调用改写与意图评测接口，不重放历史，不生成回答，不自动重试。")
    if args.dry_run:
        print("输入校验通过；未调用接口，未创建结果文件。")
        return 0
    token = (os.getenv("HUIANSWER_EVAL_TOKEN") or getpass.getpass("粘贴登录 token（输入不显示）：")).strip()
    if not token:
        raise ValueError("token 不能为空")
    headers = {"Content-Type": "application/x-www-form-urlencoded; charset=UTF-8", "Accept": "application/json",
               "username": args.username, "token": token}
    default_output = cases_path.parent.parent / "runs" / "intent" if cases_path else source.parent / "intent"
    directory = (args.output_dir or default_output).resolve() / datetime.now().strftime("%Y%m%d-%H%M%S-%f")
    directory.mkdir(parents=True, exist_ok=False)
    print(f"采集目录：{directory}")
    metadata = {"version": "intent-replay-v2", "source": str(source),
                "source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
                "base_url": args.base_url.rstrip("/"), "case_ids": [row["case_id"] for row in rows],
                "planned_cases": len(rows), "status": "running",
                "history_mode": "reuse_conversation_before_source_question" if cases_path else "reuse_conversation_before_last_matching_user_question"}
    if cases_path:
        metadata.update({"cases": str(cases_path),
                         "cases_sha256": hashlib.sha256(cases_path.read_bytes()).hexdigest()})
    collected = []
    try:
        with (directory / "results.jsonl").open("x", encoding="utf-8") as output:
            for item in rows:
                row = collect_one(item, args.base_url.rstrip("/"), headers, args.timeout)
                collected.append(row)
                output.write(json.dumps(row, ensure_ascii=False) + "\n")
                output.flush()
                actual = ",".join(result["intent"] for result in row["predictions"])
                verdict = "失败" if row["status"] != "success" else "正确" if row["correct"] else "不符预期"
                rewrite_model = (row["rewrite"] or {}).get("model") or "未取得/未记录"
                print(f"[{len(collected)}/{len(rows)}] {row['case_id']} 预期={'/'.join(row['expected_intents'])}；实际={actual or '未取得'}；{verdict}；改写模型={rewrite_model}")
                if row["error"]:
                    print("原因：" + row["error"])
                if row["http_status"] in (401, 403, 404):
                    print("认证或接口路径异常，停止本轮，已有结果保留。")
                    break
        metadata["status"] = "completed" if len(collected) == len(rows) and all(row["status"] == "success" for row in collected) else "completed_with_errors"
    except KeyboardInterrupt:
        metadata["status"] = "interrupted"
        print("\n已中断，已有结果保留。")
    except BaseException:
        metadata["status"] = "failed"
        raise
    finally:
        metadata["completed_cases"] = len(collected)
        write_json(directory / "run.json", metadata)
        summary = write_report(directory, collected)
    print(f"意图符合预期 {summary['matched_cases']}/{summary['valid_cases']}；请求失败 {summary['failed_cases']}。")
    return 0 if metadata["status"] == "completed" else 1


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    try:
        sys.exit(main())
    except Exception as error:
        print(f"采集未完成：{error}", file=sys.stderr)
        sys.exit(1)
