package com.example.tktt.search;

import com.example.tktt.config.AppProperties;
import com.example.tktt.document.ExtractedDocument;
import jakarta.annotation.PreDestroy;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.QueryBuilder;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Owns both the searchable corpus and its crawl metadata; no second database. */
@Repository
public class LuceneRepository {
    public enum Change { ADDED, UPDATED, UNCHANGED }
    private final Directory directory;
    private final VietnameseAnalyzer analyzer;
    private final VietnameseAnalyzer queryAnalyzer;
    private final IndexWriter writer;
    private final SearcherManager searchers;

    public LuceneRepository(AppProperties properties) throws IOException {
        Files.createDirectories(properties.indexDirectory());
        analyzer = new VietnameseAnalyzer();
        queryAnalyzer = new VietnameseAnalyzer(false);
        directory = FSDirectory.open(properties.indexDirectory());
        writer = new IndexWriter(directory, new IndexWriterConfig(analyzer));
        writer.commit();
        searchers = new SearcherManager(writer, null);
    }

    public synchronized Change upsert(ExtractedDocument source) throws IOException {
        Document existing = find(source.url());
        Term key = new Term("url", source.url());
        if (existing != null && source.hash().equals(existing.get("hash"))) {
            // Refresh crawl time without indexing unchanged text again.
            writer.updateNumericDocValue(key, "checkedAt", System.currentTimeMillis());
            publish();
            return Change.UNCHANGED;
        }
        Document document = new Document();
        document.add(new StringField("url", source.url(), Field.Store.YES));
        document.add(new TextField("title", source.title(), Field.Store.YES));
        document.add(new TextField("content", source.content(), Field.Store.YES));
        document.add(new StringField("domain", URI.create(source.url()).getHost(), Field.Store.YES));
        document.add(new StringField("type", source.contentType().contains("html") ? "article" : "document", Field.Store.YES));
        document.add(new StoredField("contentType", source.contentType()));
        document.add(new StringField("hash", source.hash(), Field.Store.YES));
        document.add(new StoredField("updatedAt", Instant.now().toString()));
        document.add(new NumericDocValuesField("checkedAt", System.currentTimeMillis()));
        writer.updateDocument(key, document);
        publish();
        return existing == null ? Change.ADDED : Change.UPDATED;
    }

    public synchronized boolean delete(String url) throws IOException {
        if (find(url) == null) return false;
        writer.deleteDocuments(new Term("url", url));
        publish();
        return true;
    }

    private void publish() throws IOException {
        writer.commit();
        searchers.maybeRefreshBlocking();
    }

    private Document find(String url) throws IOException {
        IndexSearcher searcher = searchers.acquire();
        try {
            TopDocs hits = searcher.search(new TermQuery(new Term("url", url)), 1);
            return hits.scoreDocs.length == 0 ? null : searcher.storedFields().document(hits.scoreDocs[0].doc);
        } finally {
            searchers.release(searcher);
        }
    }

    public SearchResponse search(String text, int page, int size, String type, String domain)
            throws IOException {
        if (text.isBlank()) return new SearchResponse(text, page, size, 0, List.of());
        QueryBuilder builder = new QueryBuilder(queryAnalyzer);
        builder.setAutoGenerateMultiTermSynonymsPhraseQuery(true);
        BooleanQuery.Builder textQuery = new BooleanQuery.Builder().setMinimumNumberShouldMatch(1);
        for (String field : new String[]{"title", "content"}) {
            Query fieldQuery = builder.createBooleanQuery(field, text, BooleanClause.Occur.MUST);
            if (fieldQuery != null) textQuery.add(new BoostQuery(fieldQuery, field.equals("title") ? 3f : 1f),
                    BooleanClause.Occur.SHOULD);
        }
        if (textQuery.build().clauses().isEmpty()) return new SearchResponse(text, page, size, 0, List.of());
        BooleanQuery.Builder query = new BooleanQuery.Builder()
                .add(textQuery.build(), BooleanClause.Occur.MUST);
        if (!type.isBlank()) query.add(new TermQuery(new Term("type", type)), BooleanClause.Occur.FILTER);
        if (!domain.isBlank()) query.add(new TermQuery(new Term("domain", domain.toLowerCase(java.util.Locale.ROOT))), BooleanClause.Occur.FILTER);
        IndexSearcher searcher = searchers.acquire();
        try {
            Query built = query.build();
            int total = searcher.count(built);
            int offset = page * size;
            List<SearchResponse.Result> results = new ArrayList<>();
            if (offset < total) {
                TopDocs hits = searcher.search(built, offset + size);
                for (int i = offset; i < hits.scoreDocs.length; i++) {
                    ScoreDoc hit = hits.scoreDocs[i];
                    Document doc = searcher.storedFields().document(hit.doc);
                    results.add(new SearchResponse.Result(doc.get("url"), doc.get("title"),
                            snippet(doc.get("content"), text), doc.get("domain"), doc.get("type"),
                            doc.get("updatedAt"), hit.score));
                }
            }
            return new SearchResponse(text, page, size, total, results);
        } finally {
            searchers.release(searcher);
        }
    }

    private String snippet(String content, String query) {
        String normalized = VietnameseAnalyzer.normalize(content);
        int match = -1;
        for (String token : VietnameseAnalyzer.normalize(query).split("\\s+")) {
            match = normalized.indexOf(token);
            if (match >= 0) break;
        }
        int start = Math.max(0, match - 70);
        int end = Math.min(content.length(), start + 260);
        return (start > 0 ? "…" : "") + content.substring(start, end) + (end < content.length() ? "…" : "");
    }

    public List<String> revisitUrls(int limit) throws IOException {
        IndexSearcher searcher = searchers.acquire();
        try {
            TopDocs hits = searcher.search(new MatchAllDocsQuery(), Math.max(1, limit),
                    new Sort(new SortField("checkedAt", SortField.Type.LONG)));
            List<String> urls = new ArrayList<>();
            for (ScoreDoc hit : hits.scoreDocs) urls.add(searcher.storedFields().document(hit.doc).get("url"));
            return urls;
        } finally {
            searchers.release(searcher);
        }
    }

    public int count() throws IOException {
        IndexSearcher searcher = searchers.acquire();
        try { return searcher.getIndexReader().numDocs(); }
        finally { searchers.release(searcher); }
    }

    public List<String> analyze(String text) throws IOException { return analyzer.tokens(text); }

    @PreDestroy
    public synchronized void close() throws IOException {
        searchers.close();
        writer.close();
        analyzer.close();
        queryAnalyzer.close();
        directory.close();
    }
}
