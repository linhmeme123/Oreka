package com.example.tktt.crawl;

import com.example.tktt.config.AppProperties;
import com.example.tktt.document.DocumentExtractor;
import com.example.tktt.document.ExtractedDocument;
import com.example.tktt.search.LuceneRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
public class CrawlService {
    private static final Logger log = LoggerFactory.getLogger(CrawlService.class);
    private final AppProperties properties;
    private final LuceneRepository repository;
    private final DocumentExtractor extractor;
    private final ObjectMapper mapper;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile CrawlStatus status = CrawlStatus.idle();
    private volatile Process process;

    public CrawlService(AppProperties properties, LuceneRepository repository,
                        DocumentExtractor extractor, ObjectMapper mapper) {
        this.properties = properties;
        this.repository = repository;
        this.extractor = extractor;
        this.mapper = mapper;
    }

    public CrawlStatus status() { return status; }

    public synchronized boolean start() {
        if (status.state().equals("RUNNING") || executor.isShutdown()) return false;
        status = new CrawlStatus("RUNNING", Instant.now().toString(), null,
                0, 0, 0, 0, 0, 0, 0, "Scrapy đang thu thập trang và tài liệu…");
        executor.submit(this::run);
        return true;
    }

    @Scheduled(fixedDelayString = "${app.crawl-interval-ms:21600000}",
            initialDelayString = "${app.crawl-interval-ms:21600000}")
    public void scheduledCrawl() { start(); }

    private void run() {
        Path staging = null;
        int fetched = 0, added = 0, updated = 0, unchanged = 0, deleted = 0, skipped = 0, errors = 0;
        String started = status.startedAt();
        try {
            staging = Files.createTempDirectory("hust-crawl-");
            Map<String, Object> config = new HashMap<>();
            config.put("seed_urls", properties.seedUrls());
            config.put("revisit_urls", repository.revisitUrls(Math.max(1, properties.maxPages() / 2)));
            config.put("max_pages", properties.maxPages());
            config.put("max_depth", properties.maxDepth());
            config.put("delay", properties.downloadDelaySeconds());
            config.put("max_file_bytes", properties.maxFileMb() * 1024L * 1024L);
            config.put("output_dir", staging.toString());
            Path input = staging.resolve("config.json");
            mapper.writeValue(input.toFile(), config);
            Process worker = new ProcessBuilder(properties.python(), properties.crawlerScript().toString(), input.toString())
                    .redirectErrorStream(true).redirectOutput(staging.resolve("crawler.log").toFile()).start();
            process = worker;
            if (!worker.waitFor(properties.crawlTimeoutSeconds(), TimeUnit.SECONDS)) {
                worker.destroyForcibly();
                throw new IllegalStateException("Thu thập quá thời gian cho phép.");
            }
            if (worker.exitValue() != 0) {
                log.error("Scrapy failed: {}", Files.readString(staging.resolve("crawler.log")));
                throw new IllegalStateException("Scrapy không chạy thành công. Kiểm tra .venv, requirements.txt và log ứng dụng.");
            }
            JsonNode summary = mapper.readTree(staging.resolve("summary.json").toFile());
            errors = summary.path("errors").asInt();
            skipped = summary.path("skipped").asInt();
            try (BufferedReader reader = Files.newBufferedReader(staging.resolve("manifest.jsonl"))) {
                for (String line; (line = reader.readLine()) != null;) {
                    JsonNode item = mapper.readTree(line);
                    fetched++;
                    try {
                        String url = item.path("url").asText();
                        boolean accepted = false;
                        if (item.path("status").asInt() == 404 || item.path("status").asInt() == 410) {
                            if (repository.delete(url)) deleted++;
                            accepted = true;
                        } else {
                            Path body = staging.resolve(item.path("file").asText()).normalize();
                            if (!body.startsWith(staging)) throw new IllegalArgumentException("Đường dẫn tài liệu không hợp lệ.");
                            ExtractedDocument document = extractor.extract(url, Files.readAllBytes(body), item.path("content_type").asText());
                            if (document == null) skipped++;
                            else {
                                switch (repository.upsert(document)) {
                                    case ADDED -> added++;
                                    case UPDATED -> updated++;
                                    case UNCHANGED -> unchanged++;
                                }
                                accepted = true;
                            }
                        }
                        if (accepted && item.path("aliases").isArray()) {
                            for (JsonNode alias : item.path("aliases")) {
                                if (!alias.asText().equals(url) && repository.delete(alias.asText())) deleted++;
                            }
                        }
                    } catch (Exception exception) {
                        errors++;
                        log.warn("Không xử lý được {}: {}", item.path("url").asText(), exception.toString());
                    }
                    status = new CrawlStatus("RUNNING", started, null, fetched, added, updated, unchanged,
                            deleted, skipped, errors, "Đang bóc tách và lập chỉ mục…");
                }
            }
            String message = fetched == 0 ? "Không lấy được tài liệu nào. Kiểm tra URL nguồn, robots.txt và kết nối mạng."
                    : "Đã hoàn tất lượt thu thập.";
            status = new CrawlStatus(errors > 0 || fetched == 0 ? "PARTIAL" : "COMPLETED", started,
                    Instant.now().toString(), fetched, added, updated, unchanged, deleted, skipped, errors, message);
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            log.error("Crawl failed", exception);
            status = new CrawlStatus("FAILED", started, Instant.now().toString(), fetched, added, updated,
                    unchanged, deleted, skipped, errors + 1, exception.getMessage());
        } finally {
            Process worker = process;
            if (worker != null && worker.isAlive()) worker.destroyForcibly();
            process = null;
            if (staging != null) {
                try (var paths = Files.walk(staging)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                } catch (Exception exception) {
                    log.warn("Không xóa được thư mục tạm {}", staging, exception);
                }
            }
        }
    }

    @PreDestroy
    public void close() throws InterruptedException {
        executor.shutdownNow();
        Process worker = process;
        if (worker != null) worker.destroyForcibly();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }
}
