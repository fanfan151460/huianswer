package com.lhsg.HuiAnswer.rag.knowledge.documentUtil.parser;

import com.lhsg.HuiAnswer.framework.exception.ClientException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.pdf.PDFParser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.parser.txt.TXTParser;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** 本课只提取平文本，不保留复杂排版，不执行 OCR。 */
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
        boolean pdf = "pdf".equals(extension);
        // TXT/Markdown 首版约定 UTF-8。Markdown 标记保留，暂不解析标题层级。
        if (!pdf) {
            metadata.set(Metadata.CONTENT_ENCODING, StandardCharsets.UTF_8.name());
        }
        Parser parser = pdf ? new PDFParser() : new TXTParser();

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
            throw new ClientException("没有提取到文字；扫描版PDF需要OCR，本阶段暂不支持");
        }
        return new ParsedDocument(text, pdf ? "application/pdf" : "text/plain");
    }
}
