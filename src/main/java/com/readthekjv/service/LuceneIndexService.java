package com.readthekjv.service;

import com.readthekjv.model.SearchResult;
import com.readthekjv.model.Verse;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.search.highlight.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Service for full-text search using Apache Lucene with an in-memory index.
 */
@Service
public class LuceneIndexService {

    private static final Logger log = LoggerFactory.getLogger(LuceneIndexService.class);

    private static final String FIELD_ID = "id";
    private static final String FIELD_BOOK = "book";
    private static final String FIELD_CHAPTER = "chapter";
    private static final String FIELD_VERSE = "verse";
    private static final String FIELD_TEXT = "text";
    private static final String FIELD_REFERENCE = "reference";

    private Directory directory;
    private StandardAnalyzer analyzer;
    private DirectoryReader reader;
    private IndexSearcher searcher;

    public LuceneIndexService() throws IOException {
        this.directory = FSDirectory.open(
                java.nio.file.Files.createTempDirectory("kjv-lucene-"));
        this.analyzer = new StandardAnalyzer();
    }

    /**
     * Builds the Lucene index from a list of verses.
     * Should be called once at startup after verses are loaded.
     */
    public void buildIndex(List<Verse> verses) throws IOException {
        log.info("Building Lucene index for {} verses...", verses.size());
        long startTime = System.currentTimeMillis();

        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE);

        try (IndexWriter writer = new IndexWriter(directory, config)) {
            for (Verse verse : verses) {
                Document doc = new Document();
                doc.add(new StoredField(FIELD_ID, verse.id()));
                doc.add(new StringField(FIELD_BOOK, verse.book(), Field.Store.YES));
                doc.add(new IntPoint(FIELD_CHAPTER, verse.chapter()));
                doc.add(new StoredField(FIELD_CHAPTER, verse.chapter()));
                doc.add(new IntPoint(FIELD_VERSE, verse.verse()));
                doc.add(new StoredField(FIELD_VERSE, verse.verse()));
                doc.add(new TextField(FIELD_TEXT, verse.text(), Field.Store.YES));
                doc.add(new StringField(FIELD_REFERENCE, verse.reference(), Field.Store.YES));
                writer.addDocument(doc);
            }
        }

        // Open reader for searching
        this.reader = DirectoryReader.open(directory);
        this.searcher = new IndexSearcher(reader);

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("Lucene index built in {}ms", elapsed);
    }

    /**
     * Performs a full-text search on verse text.
     * Supports OR logic using either "|" or "OR" between terms.
     * Examples: "vanity | vanities" or "vanity OR vanities"
     *
     * @param queryString The search query
     * @param maxResults Maximum number of results to return
     * @return SearchResult containing matching verses
     */
    public SearchResult search(String queryString, int maxResults) {
        if (searcher == null) {
            log.warn("Search attempted before index was built");
            return new SearchResult(queryString, 0, List.of());
        }

        try {
            // Preprocess query: convert "|" to "OR" for user-friendly syntax
            String processedQuery = preprocessQuery(queryString);

            QueryParser parser = new QueryParser(FIELD_TEXT, analyzer);
            parser.setDefaultOperator(QueryParser.Operator.AND);
            Query query = parser.parse(processedQuery);

            TopDocs topDocs = searcher.search(query, maxResults);
            
            // Set up highlighter
            QueryScorer scorer = new QueryScorer(query);
            Highlighter highlighter = new Highlighter(
                new SimpleHTMLFormatter("<mark>", "</mark>"),
                scorer
            );
            highlighter.setTextFragmenter(new SimpleSpanFragmenter(scorer, 150));

            List<SearchResult.VerseMatch> matches = new ArrayList<>();
            
            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                Document doc = searcher.storedFields().document(scoreDoc.doc);
                
                int id = doc.getField(FIELD_ID).numericValue().intValue();
                String book = doc.get(FIELD_BOOK);
                int chapter = doc.getField(FIELD_CHAPTER).numericValue().intValue();
                int verse = doc.getField(FIELD_VERSE).numericValue().intValue();
                String text = doc.get(FIELD_TEXT);

                // Generate highlight
                String highlight = null;
                try {
                    String[] fragments = highlighter.getBestFragments(analyzer, FIELD_TEXT, text, 1);
                    if (fragments.length > 0) {
                        highlight = fragments[0];
                    }
                } catch (Exception e) {
                    // Ignore highlighting errors
                }

                matches.add(new SearchResult.VerseMatch(id, book, chapter, verse, text, highlight));
            }

            return new SearchResult(queryString, (int) topDocs.totalHits.value, matches);

        } catch (ParseException e) {
            log.warn("Invalid search query: {}", queryString, e);
            return new SearchResult(queryString, 0, List.of());
        } catch (IOException e) {
            log.error("Search error", e);
            return new SearchResult(queryString, 0, List.of());
        }
    }

    /**
     * Preprocesses a query string to support user-friendly syntax.
     * Converts "|" to "OR" for intuitive OR logic.
     *
     * @param queryString The raw query from the user
     * @return The processed query ready for Lucene parsing
     */
    private String preprocessQuery(String queryString) {
        // Replace "|" with "OR" (handles with/without surrounding spaces)
        return queryString.replaceAll("\\s*\\|\\s*", " OR ");
    }

    /**
     * Closes the index resources.
     */
    public void close() {
        try {
            if (reader != null) {
                reader.close();
            }
            if (directory != null) {
                directory.close();
            }
        } catch (IOException e) {
            log.error("Error closing Lucene resources", e);
        }
    }
}
