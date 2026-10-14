package com.example.tktt.document;

import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

@Service
public class DocumentExtractor {
    private static final Set<String> SUPPORTED = Set.of("application/pdf", "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation", "text/plain",
            "text/html", "application/xhtml+xml");
    private static final int MAX_TEXT_LENGTH = 500_000;

    public ExtractedDocument extract(String url, byte[] bytes, String declaredType) throws Exception {
        String type = declaredType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED.contains(type)) type = new Tika().detect(bytes, URI.create(url).getPath());
        if (!SUPPORTED.contains(type)) return null;
        String title;
        String content;
        if (type.equals("text/html") || type.equals("application/xhtml+xml")) {
            org.jsoup.nodes.Document html = Jsoup.parse(new ByteArrayInputStream(bytes), null, url);
            title = html.title();
            Element heading = html.selectFirst("h1");
            if (heading != null && !heading.text().isBlank()) title = heading.text();
            html.select("script,style,nav,header,footer,aside,noscript,form").remove();
            Element main = html.selectFirst("article,main,[role=main],.news-bodyhtml,#news-bodyhtml,.detail-content,.article-content");
            content = (main == null ? html.body() : main).text();
        } else {
            Metadata metadata = new Metadata();
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, URI.create(url).getPath());
            BodyContentHandler handler = new BodyContentHandler(MAX_TEXT_LENGTH);
            ParseContext context = new ParseContext();
            PDFParserConfig pdf = new PDFParserConfig();
            pdf.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.NO_OCR);
            context.set(PDFParserConfig.class, pdf);
            new AutoDetectParser().parse(new ByteArrayInputStream(bytes), handler, metadata, context);
            title = metadata.get(TikaCoreProperties.TITLE);
            content = handler.toString();
        }
        content = content.replaceAll("\\s+", " ").trim();
        if (content.isBlank()) return null;
        if (content.length() > MAX_TEXT_LENGTH) content = content.substring(0, MAX_TEXT_LENGTH);
        if (title == null || title.isBlank()) title = URI.create(url).getPath();
        if (title == null || title.isBlank() || title.equals("/")) title = URI.create(url).getHost();
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((title + "\n" + type + "\n" + content).getBytes(StandardCharsets.UTF_8)));
        return new ExtractedDocument(url, title, content, type, hash);
    }
}
