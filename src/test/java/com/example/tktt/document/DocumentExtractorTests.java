package com.example.tktt.document;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class DocumentExtractorTests {
    private final DocumentExtractor extractor = new DocumentExtractor();
    @Test
    void extractsDocxVietnameseTextUsingTika() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("Đại học Bách khoa Hà Nội: tài liệu tuyển sinh.");
            doc.write(bytes);
        }
        ExtractedDocument result = extractor.extract("https://hust.edu.vn/tuyen-sinh.docx", bytes.toByteArray(), "application/octet-stream");
        assertTrue(result.content().contains("Bách khoa Hà Nội"));
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", result.contentType());
    }
    @Test
    void extractsPdfTextUsingTika() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage();
            pdf.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                content.showText("HUST scholarship information 2026");
                content.endText();
            }
            pdf.save(bytes);
        }
        assertTrue(extractor.extract("https://hust.edu.vn/document.pdf", bytes.toByteArray(), "application/pdf")
                .content().contains("HUST scholarship"));
    }
    @Test
    void skipsEmptyDocumentsAndImages() throws Exception {
        assertNull(extractor.extract("https://hust.edu.vn/empty.html", "<html><body></body></html>"
                .getBytes(StandardCharsets.UTF_8), "text/html"));
        byte[] png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=");
        assertNull(extractor.extract("https://hust.edu.vn/image.png", png, "image/png"));
    }
}
