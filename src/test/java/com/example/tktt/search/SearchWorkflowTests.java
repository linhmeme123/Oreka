package com.example.tktt.search;

import com.example.tktt.config.AppProperties;
import com.example.tktt.document.DocumentExtractor;
import com.example.tktt.document.ExtractedDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SearchWorkflowTests {
    @TempDir Path directory;
    private final DocumentExtractor extractor = new DocumentExtractor();
    private AppProperties properties() {
        return new AppProperties(directory, ".venv/bin/python", Path.of("crawler/hust_spider.py"),
                List.of("https://hust.edu.vn/"), 10, 2, 1, 10, 60);
    }
    private ExtractedDocument article(String url, String title, String body) throws Exception {
        return extractor.extract(url, ("<html><head><meta charset='UTF-8'></head><body>"
                + "<nav>Điều hướng</nav><article><h1>" + title + "</h1><p>" + body
                + "</p></article><footer>Chân trang</footer></body></html>")
                .getBytes(StandardCharsets.UTF_8), "text/html; charset=UTF-8");
    }
    @Test
    void extractsIndexesSearchesUpdatesPersistsAndDeletesWithoutDuplicateUrls() throws Exception {
        String url = "https://hust.edu.vn/hoc-bong.html";
        LuceneRepository repository = new LuceneRepository(properties());
        try {
            ExtractedDocument initial = article(url, "Học bổng sinh viên", "Thông báo học bổng năm 2026.");
            assertFalse(initial.content().contains("Điều hướng"));
            assertEquals(LuceneRepository.Change.ADDED, repository.upsert(initial));
            assertEquals(LuceneRepository.Change.UNCHANGED, repository.upsert(initial));
            assertEquals(1, repository.count());
            assertEquals(1, repository.search("hoc bong", 0, 10, "", "").total());
            assertEquals(1, repository.search("HỌC BỔNG", 0, 10, "article", "hust.edu.vn").total());
            assertEquals(0, repository.search("học bổng", 0, 10, "document", "").total());
            assertEquals(0, repository.search("học bổng", 0, 10, "", "sis.hust.edu.vn").total());
            assertEquals(LuceneRepository.Change.UPDATED,
                    repository.upsert(article(url, "Tuyển sinh thạc sĩ", "Đăng ký tuyển sinh sau đại học.")));
            assertEquals(0, repository.search("hoc bong", 0, 10, "", "").total());
            assertEquals(1, repository.search("tuyen sinh", 0, 10, "", "").total());
        } finally { repository.close(); }
        repository = new LuceneRepository(properties());
        try {
            assertEquals(1, repository.count());
            assertEquals(1, repository.search("tuyen sinh", 0, 10, "", "").total());
            assertTrue(repository.delete(url));
            assertFalse(repository.delete(url));
            assertEquals(0, repository.count());
        } finally { repository.close(); }
    }
    @Test
    void normalizesVietnameseAndRetainsPartialSyllables() throws Exception {
        try (VietnameseAnalyzer analyzer = new VietnameseAnalyzer()) {
            List<String> tokens = analyzer.tokens("ĐẠI HỌC BÁCH KHOA HÀ NỘI");
            assertTrue(tokens.containsAll(List.of("dai_hoc", "bach_khoa", "ha_noi", "dai")));
            assertEquals(analyzer.tokens("đào tạo"), analyzer.tokens("dao tao"));
            assertEquals(analyzer.tokens("học bổng"), analyzer.tokens("ho\u0323c bo\u0302\u0309ng"));
        }
    }
    @Test
    void paginatesAndPrioritizesTitleMatches() throws Exception {
        LuceneRepository repository = new LuceneRepository(properties());
        try {
            repository.upsert(article("https://hust.edu.vn/a", "Chương trình", "Học bổng cho sinh viên."));
            repository.upsert(article("https://hust.edu.vn/b", "Học bổng", "Học bổng cho sinh viên."));
            SearchResponse first = repository.search("học bổng", 0, 1, "", "");
            assertEquals(2, first.total());
            assertEquals("https://hust.edu.vn/b", first.results().getFirst().url());
            assertEquals("https://hust.edu.vn/a", repository.search("học bổng", 1, 1, "", "").results().getFirst().url());
            assertTrue(repository.search("học bổng", 5, 1, "", "").results().isEmpty());
            assertEquals(0, repository.search("  ", 0, 10, "", "").total());
        } finally { repository.close(); }
    }
    @Test
    void revisitsOldestDocumentsEvenWhenTextIsUnchanged() throws Exception {
        LuceneRepository repository = new LuceneRepository(properties());
        try {
            ExtractedDocument a = article("https://hust.edu.vn/a", "Thông báo", "Nội dung A");
            repository.upsert(a);
            Thread.sleep(10);
            repository.upsert(article("https://hust.edu.vn/b", "Thông báo", "Nội dung B"));
            assertEquals(a.url(), repository.revisitUrls(1).getFirst());
            Thread.sleep(10);
            repository.upsert(a);
            assertEquals("https://hust.edu.vn/b", repository.revisitUrls(1).getFirst());
        } finally { repository.close(); }
    }
}
