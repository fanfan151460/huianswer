package com.lhsg.HuiAnswer.rag.knowledge.document.parser;

import com.lhsg.HuiAnswer.framework.exception.ClientException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** 提取文档正文为纯文本，不保留复杂排版，不执行 OCR。 */
@Component
public class TikaDocumentParser {
    private final int maxCharacters;

    public TikaDocumentParser(@Value("${rag.parse.max-characters:1000000}") int maxCharacters) {
        if (maxCharacters <= 0) {
            throw new IllegalArgumentException("解析字符上限必须大于0");
        }
        this.maxCharacters = maxCharacters;
    }

    public ParsedDocument parse(InputStream input, String fileName, String extension) {
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);
        // TXT/Markdown 约定 UTF-8；Word、PDF 的编码由对应解析器处理。
        // Markdown 标记保留，暂不解析标题层级。
        if ("txt".equals(extension) || "md".equals(extension)) {
            metadata.set(Metadata.CONTENT_ENCODING, StandardCharsets.UTF_8.name());
        }
        // 根据文件内容和资源名选择已引入的解析器，支持 DOC/DOCX。
        Parser parser = new AutoDetectParser();
        ParseContext context = new ParseContext();
        PDFParserConfig pdfConfig = new PDFParserConfig();
        pdfConfig.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.NO_OCR);
        pdfConfig.setExtractInlineImages(false);
        context.set(PDFParserConfig.class, pdfConfig);
        BodyContentHandler handler = new BodyContentHandler(maxCharacters);
        try {
            parser.parse(input, handler, metadata, context);
        } catch (Exception e) {
            // 超过字符上限也按失败处理，不能把截断文本误当成完整文档。
            throw new ClientException("文档解析失败，请检查文件内容或解析字符上限");
        }
        String text = handler.toString().replace("\r\n", "\n").replace('\r', '\n').strip();
        if (text.isBlank()) {
            throw new ClientException("没有提取到文字，请检查文档内容；扫描版PDF需要OCR，本阶段暂不支持");
        }
        // 实际类型由解析器写回，不再把所有非 PDF 文件记成 text/plain。
        return new ParsedDocument(text, metadata.get(Metadata.CONTENT_TYPE));
    }
}
