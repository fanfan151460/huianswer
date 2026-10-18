package com.lhsg.HuiAnswer.rag.knowledge;

import com.lhsg.HuiAnswer.framework.exception.ClientException;
import com.lhsg.HuiAnswer.rag.knowledge.document.parser.TikaDocumentParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class TikaDocumentParserTest {
    @Test
    void shouldExtractDocxParagraphsAndTableInRowOrder() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText("渠道统计");
            document.createParagraph().createRun().setText("下面是各渠道的访问数据。");
            XWPFTable table = document.createTable(3, 3);
            String[][] cells = {
                    {"渠道", "PV", "UV"},
                    {"微信", "100", "80"},
                    {"短信", "50", "20"}
            };
            for (int row = 0; row < cells.length; row++) {
                for (int column = 0; column < cells[row].length; column++) {
                    table.getRow(row).getCell(column).setText(cells[row][column]);
                }
            }
            document.createParagraph().createRun().setText("表格后的说明。");
            document.write(bytes);
        }
        var parsed = new TikaDocumentParser(2000).parse(
                new ByteArrayInputStream(bytes.toByteArray()), "channels.docx", "docx");
        String normalized = parsed.text().replaceAll("\\s+", " ").strip();
        assertTrue(normalized.contains("渠道 PV UV 微信 100 80 短信 50 20"), parsed.text());
        assertTrue(normalized.indexOf("下面是各渠道的访问数据。") < normalized.indexOf("渠道 PV UV"),
                parsed.text());
        assertTrue(normalized.indexOf("短信 50 20") < normalized.indexOf("表格后的说明。"),
                parsed.text());
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                parsed.mediaType());
    }

    @Test
    void shouldKeepMarkdownTextAndUtf8Characters() {
        String content = "# 退款规则\n七天内可申请退款。";
        var parsed = new TikaDocumentParser(1000).parse(
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), "rules.md", "md");
        assertEquals(content, parsed.text());
    }

    @Test
    void shouldExtractTextFromActualPdfWithoutOcr() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(40, 700);
                content.showText("Annual leave needs approval.");
                content.endText();
            }
            document.save(bytes);
        }
        var parsed = new TikaDocumentParser(1000).parse(new ByteArrayInputStream(bytes.toByteArray()),
                "policy.pdf", "pdf");
        assertTrue(parsed.text().contains("Annual leave needs approval."));
        assertEquals("application/pdf", parsed.mediaType());
    }

    @Test
    void shouldFailRatherThanSilentlyTruncateTextOverCharacterLimit() {
        byte[] bytes = "知识库测试".repeat(100).getBytes(StandardCharsets.UTF_8);
        assertThrows(ClientException.class, () -> new TikaDocumentParser(20)
                .parse(new ByteArrayInputStream(bytes), "sample.txt", "txt"));
    }

    @Test
    void shouldRejectPdfWithoutExtractableText() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.save(bytes);
        }
        assertThrows(ClientException.class, () -> new TikaDocumentParser(1000)
                .parse(new ByteArrayInputStream(bytes.toByteArray()), "scan.pdf", "pdf"));
    }
}
