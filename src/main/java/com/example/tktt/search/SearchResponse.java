package com.example.tktt.search;

import java.util.List;

public record SearchResponse(String query, int page, int size, long total, List<Result> results) {
    public record Result(String url, String title, String snippet, String domain, String type,
                         String updatedAt, float score) { }
}
