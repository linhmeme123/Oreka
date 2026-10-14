package com.example.tktt.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;
import java.util.List;

@Validated
@ConfigurationProperties("app")
public record AppProperties(
        @DefaultValue("data/index") Path indexDirectory,
        @DefaultValue(".venv/bin/python") String python,
        @DefaultValue("crawler/hust_spider.py") Path crawlerScript,
        @NotEmpty @DefaultValue("https://hust.edu.vn/") List<String> seedUrls,
        @Min(1) @DefaultValue("100") int maxPages,
        @Min(1) @DefaultValue("3") int maxDepth,
        @Min(1) @DefaultValue("1") int downloadDelaySeconds,
        @Min(1) @DefaultValue("10") int maxFileMb,
        @Min(1) @DefaultValue("1800") int crawlTimeoutSeconds) {
}
