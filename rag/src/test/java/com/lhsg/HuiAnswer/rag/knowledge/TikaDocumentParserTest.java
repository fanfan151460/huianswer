package com.lhsg.HuiAnswer.rag.knowledge;

import com.lhsg.HuiAnswer.framework.exception.ClientException;
import com.lhsg.HuiAnswer.rag.knowledge.document.parser.TikaDocumentParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class TikaDocumentParserTest {
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
