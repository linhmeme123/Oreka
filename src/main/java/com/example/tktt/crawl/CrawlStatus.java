package com.example.tktt.crawl;

public record CrawlStatus(String state, String startedAt, String finishedAt, int fetched,
                          int added, int updated, int unchanged, int deleted, int skipped,
                          int errors, String message) {
    public static CrawlStatus idle() {
        return new CrawlStatus("IDLE", null, null, 0, 0, 0, 0, 0, 0, 0, "Chưa thu thập dữ liệu.");
    }
}
