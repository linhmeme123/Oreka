package com.example.tktt.search;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.charfilter.MappingCharFilter;
import org.apache.lucene.analysis.charfilter.NormalizeCharMap;
import org.apache.lucene.analysis.core.FlattenGraphFilter;
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.synonym.SynonymGraphFilter;
import org.apache.lucene.analysis.synonym.SynonymMap;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.util.CharsRef;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Small dictionary-based segmenter; retains syllables to support partial queries. */
public class VietnameseAnalyzer extends Analyzer {
    private final SynonymMap dictionary;
    private final NormalizeCharMap characters;
    private final boolean indexing;

    public VietnameseAnalyzer() throws IOException {
        this(true);
    }

    public VietnameseAnalyzer(boolean indexing) throws IOException {
        this.indexing = indexing;
        NormalizeCharMap.Builder mapping = new NormalizeCharMap.Builder();
        mapping.add("đ", "d");
        mapping.add("Đ", "D");
        // Vietnamese input may use decomposed Unicode accents, including pasted PDF text.
        for (char mark = '\u0300'; mark <= '\u036f'; mark++) mapping.add(String.valueOf(mark), "");
        characters = mapping.build();
        SynonymMap.Builder synonyms = new SynonymMap.Builder(true);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                Objects.requireNonNull(getClass().getResourceAsStream("/vietnamese-words.txt")),
                StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String normalized = normalize(line.trim());
                synonyms.add(new CharsRef(normalized.replace(' ', SynonymMap.WORD_SEPARATOR)),
                        new CharsRef(normalized.replace(' ', '_')), true);
            }
        }
        dictionary = synonyms.build();
    }

    @Override
    protected Reader initReader(String fieldName, Reader reader) {
        return new MappingCharFilter(characters, reader);
    }

    @Override
    protected TokenStreamComponents createComponents(String fieldName) {
        StandardTokenizer tokenizer = new StandardTokenizer();
        TokenStream stream = new LowerCaseFilter(tokenizer);
        stream = new ASCIIFoldingFilter(stream);
        stream = new SynonymGraphFilter(stream, dictionary, false);
        return new TokenStreamComponents(tokenizer, indexing ? new FlattenGraphFilter(stream) : stream);
    }

    public List<String> tokens(String text) throws IOException {
        List<String> tokens = new ArrayList<>();
        try (TokenStream stream = tokenStream("content", text)) {
            CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken()) tokens.add(term.toString());
            stream.end();
        }
        return tokens;
    }

    public static String normalize(String text) {
        return Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").replace('đ', 'd');
    }
}
