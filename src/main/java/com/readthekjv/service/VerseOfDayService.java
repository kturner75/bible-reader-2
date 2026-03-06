package com.readthekjv.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.readthekjv.model.entity.VerseOfDay;
import com.readthekjv.repository.VerseOfDayRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.Optional;

/**
 * Generates a daily AI-selected Bible verse using OpenAI Chat Completions.
 * One verse per calendar day (UTC), stored in the verse_of_day table.
 *
 * <p>Generation runs:
 * <ul>
 *   <li>At application startup (async, non-blocking) — populates today's row if missing.</li>
 *   <li>Daily at midnight UTC via {@code @Scheduled} — pre-populates the new day's verse.</li>
 * </ul>
 *
 * <p>Graceful degradation: if the API key is missing or the OpenAI call fails,
 * the error is logged and suppressed — the frontend falls back to its curated list.
 */
@Service
public class VerseOfDayService {

    private static final Logger log = LoggerFactory.getLogger(VerseOfDayService.class);

    private static final String OPENAI_CHAT_URL = "https://api.openai.com/v1/chat/completions";

    private final VerseOfDayRepository repository;
    private final BibleService bibleService;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Value("${tts.api-key:}")          // reuses ${OPENAI_API_KEY:} mapped by TtsService config
    private String apiKey;

    @Value("${votd.enabled:true}")
    private boolean enabled;

    @Value("${votd.model:gpt-4o-mini}")
    private String model;

    public VerseOfDayService(VerseOfDayRepository repository, BibleService bibleService) {
        this.repository   = repository;
        this.bibleService = bibleService;
        this.httpClient   = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns today's verse-of-day row (UTC), if it has been generated.
     */
    @Transactional(readOnly = true)
    public Optional<VerseOfDay> getTodaysVerse() {
        return repository.findById(LocalDate.now(ZoneOffset.UTC));
    }

    // ── Scheduling ────────────────────────────────────────────────────────────

    /**
     * On startup: generate today's verse asynchronously so it doesn't block app startup.
     * If the row already exists (e.g. after a restart), this is a no-op.
     */
    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void generateOnStartup() {
        generateForDate(LocalDate.now(ZoneOffset.UTC));
    }

    /**
     * Daily cron at midnight UTC: generate the new day's verse right as the day turns.
     */
    @Scheduled(cron = "0 0 0 * * *", zone = "UTC")
    public void generateDaily() {
        generateForDate(LocalDate.now(ZoneOffset.UTC));
    }

    // ── Core generation ───────────────────────────────────────────────────────

    /**
     * Generates and persists the verse-of-day for {@code date}.
     * Idempotent: skips if a row already exists for that date.
     * All failures are logged and suppressed — never throws.
     */
    @Transactional
    public void generateForDate(LocalDate date) {
        if (!enabled) {
            log.debug("Verse of the day generation is disabled");
            return;
        }
        if (apiKey == null || apiKey.isBlank()) {
            log.debug("OPENAI_API_KEY not set — skipping verse of the day generation");
            return;
        }
        if (repository.existsById(date)) {
            log.debug("Verse of the day for {} already exists — skipping", date);
            return;
        }

        log.info("Generating verse of the day for {}", date);
        try {
            String prompt = buildPrompt(date);
            String responseBody = callOpenAiChat(prompt);
            if (responseBody == null) return;

            VotdResult result = parseOpenAiResponse(responseBody);
            if (result == null) return;

            Optional<Integer> verseId = bibleService.parseAndResolve(result.reference());
            if (verseId.isEmpty()) {
                log.warn("Could not resolve KJV reference '{}' returned by OpenAI — skipping", result.reference());
                return;
            }

            VerseOfDay votd = new VerseOfDay(date, verseId.get(), result.blurb());
            repository.save(votd);
            log.info("Verse of the day for {} set to verseId={} ({})", date, verseId.get(), result.reference());

        } catch (Exception e) {
            log.error("Failed to generate verse of the day for {}: {}", date, e.getMessage(), e);
        }
    }

    // ── Prompt building ───────────────────────────────────────────────────────

    private String buildPrompt(LocalDate date) {
        String dayOfWeek  = date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        String monthName  = date.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        int    day        = date.getDayOfMonth();
        int    year       = date.getYear();

        return String.format(
                "Select one verse from the King James Bible for %s, %s %d, %d.%n%n" +
                "Guidelines:%n" +
                "- Choose verses commonly used in devotionals, Scripture memorization, or Sunday school " +
                "(well-known passages people return to repeatedly).%n" +
                "- Consider the season and date: is it near a major Christian observance " +
                "(Advent, Christmas, Lent, Holy Week, Easter, Pentecost)? " +
                "Is it a new month, new season, or notable date? " +
                "Let the theme reflect the time of year naturally.%n" +
                "- Prefer verses that are clear, complete thoughts in a single verse (not fragments).%n" +
                "- Draw from both Old and New Testaments across the year.%n%n" +
                "Return ONLY a JSON object with no markdown: " +
                "{\"reference\": \"Book Chapter:Verse\", " +
                "\"blurb\": \"2-3 sentences connecting this verse to the season or theme and why it is worth meditating on today.\"}",
                dayOfWeek, monthName, day, year
        );
    }

    // ── OpenAI HTTP call ──────────────────────────────────────────────────────

    private String callOpenAiChat(String userPrompt) throws Exception {
        String requestBody = objectMapper.writeValueAsString(new java.util.HashMap<String, Object>() {{
            put("model", model);
            put("temperature", 0.7);
            put("messages", new Object[]{
                    new java.util.HashMap<String, String>() {{
                        put("role", "system");
                        put("content", "You are a devotional curator. Always respond with valid JSON only, no markdown, no code fences.");
                    }},
                    new java.util.HashMap<String, String>() {{
                        put("role", "user");
                        put("content", userPrompt);
                    }}
            });
        }});

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(OPENAI_CHAT_URL))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(60))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            log.error("OpenAI Chat API error {}: {}", response.statusCode(), response.body());
            return null;
        }

        return response.body();
    }

    // ── Response parsing ──────────────────────────────────────────────────────

    private VotdResult parseOpenAiResponse(String responseBody) {
        try {
            JsonNode root    = objectMapper.readTree(responseBody);
            String   content = root.path("choices").get(0)
                                   .path("message").path("content").asText().trim();

            // Strip markdown code fences if the model wraps its JSON anyway
            if (content.startsWith("```")) {
                content = content.replaceAll("```(?:json)?\\s*", "").replaceAll("```\\s*$", "").trim();
            }

            JsonNode votd      = objectMapper.readTree(content);
            String   reference = votd.path("reference").asText(null);
            String   blurb     = votd.path("blurb").asText(null);

            if (reference == null || reference.isBlank()) {
                log.warn("OpenAI returned no reference in: {}", content);
                return null;
            }

            return new VotdResult(reference.trim(), blurb != null ? blurb.trim() : null);

        } catch (Exception e) {
            log.warn("Failed to parse OpenAI response: {}", e.getMessage());
            return null;
        }
    }

    private record VotdResult(String reference, String blurb) {}
}
