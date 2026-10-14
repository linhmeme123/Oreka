package com.example.tktt.crawl;

import com.example.tktt.config.AppProperties;
import com.example.tktt.document.DocumentExtractor;
import com.example.tktt.search.LuceneRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CrawlServiceTests {
    @TempDir Path directory;
    private AppProperties properties(String python, Path script) {
        return new AppProperties(directory.resolve("index"), python, script,
                List.of("https://hust.edu.vn/"), 10, 2, 1, 10, 30);
    }

    private void fixture(Path script, String title, int status) throws Exception {
        Files.writeString(script, """
                import json, sys, time
                from pathlib import Path
                time.sleep(0.15)
                config = json.loads(Path(sys.argv[1]).read_text())
                out = Path(config['output_dir'])
                (out / 'body').write_text('<html><meta charset="UTF-8"><article><h1>%s</h1><p>Sinh viên HUST</p></article></html>')
                item = {'url': 'https://hust.edu.vn/news', 'status': %d, 'file': 'body', 'content_type': 'text/html'}
                (out / 'manifest.jsonl').write_text(json.dumps(item) + '\\n')
                (out / 'summary.json').write_text(json.dumps({'errors': 0, 'skipped': 0}))
                """.formatted(title, status));
    }

    private void await(CrawlService service) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        while (service.status().state().equals("RUNNING") && System.nanoTime() < deadline) Thread.sleep(20);
        assertNotEquals("RUNNING", service.status().state());
    }

    @Test
    void runsManualAndScheduledImportsAndRejectsOverlappingJobs() throws Exception {
        Path script = directory.resolve("fixture.py");
        fixture(script, "Học bổng", 200);
        AppProperties properties = properties(Path.of(".venv/bin/python").toAbsolutePath().toString(), script);
        LuceneRepository repository = new LuceneRepository(properties);
        CrawlService service = new CrawlService(properties, repository, new DocumentExtractor(), JsonMapper.builder().build());
        try {
            assertTrue(service.start());
            assertFalse(service.start());
            await(service);
            assertEquals("COMPLETED", service.status().state(), service.status().message());
            assertEquals(1, service.status().added());
            assertEquals(1, repository.search("hoc bong", 0, 10, "", "").total());
            service.scheduledCrawl();
            await(service);
            assertEquals(1, service.status().unchanged());
            fixture(script, "Tuyển sinh", 200);
            assertTrue(service.start());
            await(service);
            assertEquals(1, service.status().updated());
            assertEquals(0, repository.search("hoc bong", 0, 10, "", "").total());
            fixture(script, "Tuyển sinh", 410);
            assertTrue(service.start());
            await(service);
            assertEquals(1, service.status().deleted());
            assertEquals(0, repository.count());
        } finally { service.close(); repository.close(); }
    }

    @Test
    void reportsMissingCrawlerWithoutClaimingSuccess() throws Exception {
        AppProperties properties = properties("/missing-python-for-hust-test", directory.resolve("fixture.py"));
        LuceneRepository repository = new LuceneRepository(properties);
        CrawlService service = new CrawlService(properties, repository, new DocumentExtractor(), JsonMapper.builder().build());
        try {
            assertTrue(service.start());
            await(service);
            assertEquals("FAILED", service.status().state());
            assertEquals(1, service.status().errors());
            assertEquals(0, repository.count());
        } finally { service.close(); repository.close(); }
    }
}
