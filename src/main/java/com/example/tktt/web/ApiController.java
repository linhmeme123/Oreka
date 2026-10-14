package com.example.tktt.web;

import com.example.tktt.crawl.CrawlService;
import com.example.tktt.crawl.CrawlStatus;
import com.example.tktt.config.AppProperties;
import com.example.tktt.search.LuceneRepository;
import com.example.tktt.search.SearchResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final LuceneRepository repository;
    private final CrawlService crawler;
    private final AppProperties properties;
    private final long crawlIntervalMs;

    public ApiController(LuceneRepository repository, CrawlService crawler, AppProperties properties,
                         @Value("${app.crawl-interval-ms:21600000}") long crawlIntervalMs) {
        this.repository = repository;
        this.crawler = crawler;
        this.properties = properties;
        this.crawlIntervalMs = crawlIntervalMs;
    }

    @GetMapping("/search")
    public SearchResponse search(@RequestParam(defaultValue = "") @Size(max = 300) String q,
                                 @RequestParam(defaultValue = "0") @Min(0) @Max(100) int page,
                                 @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size,
                                 @RequestParam(defaultValue = "") String type,
                                 @RequestParam(defaultValue = "") @Size(max = 253) String domain) throws Exception {
        if (!type.isBlank() && !type.equals("article") && !type.equals("document")) {
            throw new IllegalArgumentException("Loại nội dung phải là article hoặc document.");
        }
        return repository.search(q.trim(), page, size, type, domain.trim());
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() throws Exception {
        return Map.of("documents", repository.count(), "crawl", crawler.status(),
                "maxPages", properties.maxPages(), "crawlIntervalMs", crawlIntervalMs);
    }

    @GetMapping("/crawl/status")
    public CrawlStatus crawlStatus() { return crawler.status(); }

    @PostMapping("/crawl")
    public ResponseEntity<CrawlStatus> crawl() {
        boolean started = crawler.start();
        return ResponseEntity.status(started ? HttpStatus.ACCEPTED : HttpStatus.CONFLICT).body(crawler.status());
    }

    @GetMapping("/analyze")
    public Map<String, Object> analyze(@RequestParam @Size(max = 300) String text) throws Exception {
        return Map.of("text", text, "tokens", repository.analyze(text));
    }
}
