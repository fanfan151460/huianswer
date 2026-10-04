package com.lhsg.HuiAnswer.rag.knowledge.documentUtil.parser;

/** 解析结果是完整文本；预览截断由接口层决定，后续切分使用完整文本。 */
public record ParsedDocument(String text, String mediaType) {
}
