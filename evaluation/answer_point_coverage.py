"""必要回答要点覆盖率；Ragas 忠实度与事实 F1 仍单独保留。"""

import json
from typing import Literal

from pydantic import BaseModel, Field


class PointVerdict(BaseModel):
    index: int = Field(ge=1, description="必要要点的编号，从1开始")
    verdict: Literal[0, 1] = Field(description="实际回答正确覆盖该要点为1，否则为0")
    reason: str = Field(min_length=1, description="用中文说明该要点是否被回答覆盖")


class PointVerdicts(BaseModel):
    judgments: list[PointVerdict]


async def score_answer_points(row, judge):
    points = row["expected_answer_points"]
    if not points:
        raise ValueError("本题没有配置必要回答要点")
    payload = {
        "question": row["user_input"],
        "actual_history": row.get("warmup_history", []),
        "answer": row["response"],
        "required_points": [{"index": i, "point": point} for i, point in enumerate(points, 1)],
    }
    prompt = (
        "请逐项核验实际回答是否正确覆盖给定的必要回答要点。\n"
        "规则：\n"
        "1. 问题和实际历史仅用于理解对象和指代，不能把历史内容当成这次回答已经说出。\n"
        "2. 允许同义表达、结合问题明确的主体省略，以及将分散在多句中的内容合并理解；"
        "不要求重复产品全名，也不要求逐字复述标准答案。\n"
        "3. 数值、单位、计时起点、适用条件及否定方向必须正确。"
        "遗漏必要条件、含糊到无法确认，或回答中出现相矛盾的结论，均记0。\n"
        "4. 额外补充不扣覆盖率，除非它与必要要点矛盾；"
        "额外事实的资料依据由另一项忠实度检查。\n"
        "5. 对排错提醒，明确给出正确结论且没有对应错误即可，"
        "不要求逐字复述每一条禁止说法；用户明确询问的错误前提仍需得到纠正。\n"
        "6. 每个编号必须返回一次，verdict仅为0或1，并用中文说明原因。"
        "只返回所要求的JSON，不要增减要点。\n\n"
        + json.dumps(payload, ensure_ascii=False)
    )
    response = await judge.agenerate(prompt, PointVerdicts)
    judgments = response.judgments
    if sorted(item.index for item in judgments) != list(range(1, len(points) + 1)):
        raise ValueError("覆盖率评委返回的编号缺失、重复或越界")
    if any(not item.reason.strip() for item in judgments):
        raise ValueError("覆盖率评委未提供有效判定理由")
    judgments = sorted(judgments, key=lambda item: item.index)
    covered = sum(item.verdict for item in judgments)
    return {
        "score": covered / len(points),
        "covered_points": covered,
        "total_points": len(points),
        "judgments": [
            {"index": item.index, "point": points[item.index - 1],
             "verdict": item.verdict, "reason": item.reason}
            for item in judgments
        ],
    }
