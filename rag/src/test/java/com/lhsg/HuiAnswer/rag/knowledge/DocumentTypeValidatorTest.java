package com.lhsg.HuiAnswer.rag.knowledge;

import com.lhsg.HuiAnswer.framework.exception.ClientException;
import com.lhsg.HuiAnswer.rag.knowledge.document.parser.DocumentTypeValidator;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class DocumentTypeValidatorTest {
    private final DocumentTypeValidator validator = new DocumentTypeValidator();

    @Test
    void acceptsUtf8TextAndMarkdownWithoutTrustingBrowserContentType() {
        byte[] text = "# 退款规则\n七天内可申请退款。".getBytes(StandardCharsets.UTF_8);
        for (String extension : new String[]{"txt", "md"}) {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "rules." + extension, "application/octet-stream", text);
            assertEquals("text/plain", validator.validate(file, extension));
        }
    }

    @Test
    void acceptsActualPdf() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.save(bytes);
        }
        MockMultipartFile file = new MockMultipartFile(
                "file", "rules.pdf", "application/pdf", bytes.toByteArray());
        assertEquals("application/pdf", validator.validate(file, "pdf"));
    }

    @Test
    void acceptsActualDocx() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "rules.docx", "application/octet-stream", docx());
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                validator.validate(file, "docx"));
    }

    @Test
    void rejectsPlainTextRenamedToPdfOrWord() {
        for (String extension : new String[]{"pdf", "doc", "docx"}) {
            MockMultipartFile file = new MockMultipartFile("file", "fake." + extension,
                    "application/pdf", "This is plain text.".getBytes(StandardCharsets.UTF_8));
            assertThrows(ClientException.class, () -> validator.validate(file, extension));
        }
    }

    @Test
    void rejectsDocxRenamedToPlainText() throws Exception {
        byte[] bytes = docx();
        for (String extension : new String[]{"txt", "md"}) {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "fake." + extension, "text/plain", bytes);
            assertThrows(ClientException.class, () -> validator.validate(file, extension));
        }
    }

    @Test
    void rejectsOrdinaryZipRenamedToDocx() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("notes.txt"));
            zip.write("Just a zip archive.".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        MockMultipartFile file = new MockMultipartFile("file", "fake.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                bytes.toByteArray());
        assertThrows(ClientException.class, () -> validator.validate(file, "docx"));
    }

    @Test
    void rejectsExcelRenamedToDocxAlthoughBothAreZipContainers() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            workbook.createSheet("Sheet1").createRow(0).createCell(0).setCellValue("Excel content");
            workbook.write(bytes);
        }
        MockMultipartFile file = new MockMultipartFile("file", "fake.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                bytes.toByteArray());
        assertThrows(ClientException.class, () -> validator.validate(file, "docx"));
    }

    private byte[] docx() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText("真实的 Word 文档内容。");
            document.write(bytes);
        }
        return bytes.toByteArray();
    }
}
