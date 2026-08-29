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
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Generates a daily AI-selected Bible verse using Chat Completions
 * (OpenAI or xAI). One verse per calendar day (UTC), stored in the
 * verse_of_day table.
 *
 * <p>Set {@code VOTD_PROVIDER=xai} to switch providers. xAI calls use a
 * SuperGrok OAuth access token when {@link XaiOAuthTokenManager} has one,
 * otherwise {@code XAI_API_KEY}. {@code VOTD_MODEL} overrides the provider
 * default ({@code gpt-4o-mini} / {@code grok-3-mini}).
 *
 * <p>Generation runs:
 * <ul>
 *   <li>At application startup (async, non-blocking) — populates today's row if missing.</li>
 *   <li>Daily at midnight UTC via {@code @Scheduled} — pre-populates the new day's verse.</li>
 * </ul>
 *
 * <p>Graceful degradation: if the API key is missing, the provider is unknown,
 * or the chat call fails, the error is logged and suppressed — the frontend
 * falls back to its curated list. An unset {@code votd.provider} defaults to
 * openai; any other value that is not {@code openai} or {@code xai} fails closed.
 */
@Service
public class VerseOfDayService {

    private static final Logger log = LoggerFactory.getLogger(VerseOfDayService.class);

    private static final String OPENAI_CHAT_URL = "https://api.openai.com/v1/chat/completions";
    private static final String XAI_CHAT_URL    = "https://api.x.ai/v1/chat/completions";
    private static final String OPENAI_DEFAULT_MODEL = "gpt-4o-mini";
    private static final String XAI_DEFAULT_MODEL    = "grok-3-mini";

    private final VerseOfDayRepository repository;
    private final BibleService bibleService;
    private final XaiOAuthTokenManager xaiOAuthTokenManager;
    private final ObjectMapper objectMapper;

    private HttpClient httpClient;

    @Value("${tts.api-key:}")          // openai: reuses ${OPENAI_API_KEY:} mapped by TtsService config
    private String openAiKey;

    @Value("${XAI_API_KEY:}")
    private String xaiKey;

    @Value("${votd.enabled:true}")
    private boolean enabled;

    @Value("${votd.provider:openai}")
    private String provider;

    @Value("${votd.model:}")
    private String model;

    public VerseOfDayService(VerseOfDayRepository repository, BibleService bibleService,
                             XaiOAuthTokenManager xaiOAuthTokenManager) {
        this.repository   = repository;
        this.bibleService = bibleService;
        this.xaiOAuthTokenManager = xaiOAuthTokenManager;
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
        if (!isKnownProvider()) {
            log.warn("Unknown votd.provider '{}' — expected openai or xai; skipping verse of the day generation",
                    provider);
            return;
        }
        // existsById before any OAuth refresh: a row already present means we will not
        // call xAI, so do not spend a refresh (xAI rotates the refresh token on every use).
        if (repository.existsById(date)) {
            log.debug("Verse of the day for {} already exists — skipping", date);
            return;
        }
        if (!hasLocalAuth()) {
            log.debug("{} not set — skipping verse of the day generation",
                    isXai() ? "xAI OAuth / XAI_API_KEY" : "OPENAI_API_KEY");
            return;
        }

        log.info("Generating verse of the day for {}", date);
        try {
            // Collect references used in the last 60 days so the model avoids repeats
            List<String> recentRefs = repository.findTop365ByOrderByDateDesc().stream()
                    .map(v -> bibleService.getVerse(v.getVerseId()))
                    .filter(Optional::isPresent)
                    .map(opt -> opt.get().reference())
                    .collect(Collectors.toList());

            String prompt = buildPrompt(date, recentRefs);
            String responseBody = callChatCompletions(prompt);
            if (responseBody == null) return;

            VotdResult result = parseChatResponse(responseBody);
            if (result == null) return;

            // Strip verse ranges (e.g. "4:6-7" → "4:6") — parser handles single verses only
            String ref = result.reference().replaceAll("-\\d+$", "").trim();
            Optional<Integer> verseId = bibleService.parseAndResolve(ref);
            if (verseId.isEmpty()) {
                log.warn("Could not resolve KJV reference '{}' returned by the model — skipping", result.reference());
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

    private String buildPrompt(LocalDate date, List<String> recentRefs) {
        String dayOfWeek  = date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        String monthName  = date.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        int    day        = date.getDayOfMonth();
        int    year       = date.getYear();

        String exclusion = recentRefs.isEmpty() ? "" :
                "%n- Do NOT select any of the following verses, which have already been used recently:%n" +
                "  " + String.join(", ", recentRefs) + "%n";

        return String.format(
                "Select one verse from the King James Bible for %s, %s %d, %d.%n%n" +
                "Guidelines:%n" +
                "- Choose verses commonly used in devotionals, Scripture memorization, or Sunday school " +
                "(well-known passages people return to repeatedly).%n" +
                "- Consider the season and date: is it near a major Christian observance " +
                "(Advent, Christmas, Lent, Holy Week, Easter, Pentecost)? " +
                "Is it a new month, new season, or notable date? " +
                "Let the theme reflect the time of year naturally.%n" +
                "- Choose a single verse only — never a range. Write '4:6' not '4:6-7'.%n" +
                "- Prefer verses that are clear, complete thoughts in a single verse (not fragments).%n" +
                "- Draw from both Old and New Testaments across the year." +
                exclusion + "%n" +
                "Return ONLY a JSON object with no markdown: " +
                "{\"reference\": \"Book Chapter:Verse\", " +
                "\"blurb\": \"2-3 sentences connecting this verse to the season or theme and why it is worth meditating on today.\"}",
                dayOfWeek, monthName, day, year
        );
    }

    // ── Provider resolution (mirrors WhisperService) ──────────────────────────

    /**
     * Unset (null) defaults to openai. Empty-after-trim or any value other than
     * openai | xai is unknown and must not fall through to OpenAI.
     */
    boolean isKnownProvider() {
        String p = normalizedProvider();
        return p == null || "openai".equalsIgnoreCase(p) || "xai".equalsIgnoreCase(p);
    }

    boolean isXai() {
        return "xai".equalsIgnoreCase(normalizedProvider());
    }

    private String normalizedProvider() {
        return provider == null ? null : provider.trim();
    }

    String resolvedUrl() {
        return isXai() ? XAI_CHAT_URL : OPENAI_CHAT_URL;
    }

    String resolvedModel() {
        if (model != null && !model.isBlank()) {
            return model;
        }
        return isXai() ? XAI_DEFAULT_MODEL : OPENAI_DEFAULT_MODEL;
    }

    String resolvedKey() {
        return isXai() ? xaiKey : openAiKey;
    }

    /**
     * Local credential check — no token-endpoint call. OAuth refresh happens
     * only in {@link #resolvedBearer()} when we are about to call xAI.
     */
    boolean hasLocalAuth() {
        if (isXai()) {
            boolean oauthReady = xaiOAuthTokenManager != null && xaiOAuthTokenManager.isConfigured();
            String key = xaiKey;
            return oauthReady || (key != null && !key.isBlank());
        }
        return openAiKey != null && !openAiKey.isBlank();
    }

    /**
     * Bearer for the chat call: SuperGrok OAuth access token when xAI and
     * present, otherwise the static provider API key.
     */
    String resolvedBearer() {
        if (isXai() && xaiOAuthTokenManager != null) {
            Optional<String> oauth = xaiOAuthTokenManager.getAccessToken();
            if (oauth.isPresent() && !oauth.get().isBlank()) {
                return oauth.get();
            }
        }
        return resolvedKey();
    }

    // ── Chat Completions HTTP call ────────────────────────────────────────────

    /**
     * Chat Completions JSON body — same shape for OpenAI and xAI
     * ({@code model}, {@code temperature}, {@code messages}). Not the Responses API.
     */
    String buildChatRequestBody(String userPrompt) throws Exception {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("model", resolvedModel());
        body.put("temperature", 0.7);
        body.put("messages", java.util.List.of(
                java.util.Map.of("role", "system",
                        "content", "You are a devotional curator. Always respond with valid JSON only, no markdown, no code fences."),
                java.util.Map.of("role", "user", "content", userPrompt)
        ));
        return objectMapper.writeValueAsString(body);
    }

    private String callChatCompletions(String userPrompt) throws Exception {
        String requestBody = buildChatRequestBody(userPrompt);

        log.debug("VOTD provider={} url={} model={}", provider, resolvedUrl(), resolvedModel());

        String bearer = resolvedBearer();
        if (bearer == null || bearer.isBlank()) {
            return null;
        }

        HttpResponse<String> response = sendChat(requestBody, bearer);
        if (isXai() && response.statusCode() == 401 && wasOAuthBearer(bearer)) {
            xaiOAuthTokenManager.invalidate();
            String retryBearer = retryXaiBearer(bearer);
            if (retryBearer != null) {
                log.warn("event=xai_oauth_rejected retrying_votd");
                response = sendChat(requestBody, retryBearer);
            }
        }

        if (response.statusCode() != 200) {
            log.error("Chat Completions API error {}: {}", response.statusCode(), response.body());
            return null;
        }

        return response.body();
    }

    private boolean wasOAuthBearer(String bearer) {
        if (!isXai() || xaiOAuthTokenManager == null || bearer == null || bearer.isBlank()) {
            return false;
        }
        String key = resolvedKey();
        return key == null || key.isBlank() || !bearer.equals(key);
    }

    /** After invalidate(): a fresh access token if it differs, else the API key. */
    private String retryXaiBearer(String rejectedBearer) {
        Optional<String> refreshed = xaiOAuthTokenManager.getAccessToken();
        if (refreshed.isPresent() && !refreshed.get().isBlank() && !refreshed.get().equals(rejectedBearer)) {
            return refreshed.get();
        }
        String key = resolvedKey();
        if (key != null && !key.isBlank() && !key.equals(rejectedBearer)) {
            return key;
        }
        return null;
    }

    private HttpResponse<String> sendChat(String requestBody, String bearer) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(resolvedUrl()))
                .header("Authorization", "Bearer " + bearer)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(60))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    // ── Response parsing ──────────────────────────────────────────────────────

    private VotdResult parseChatResponse(String responseBody) {
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
                log.warn("Chat Completions returned no reference in: {}", content);
                return null;
            }

            return new VotdResult(reference.trim(), blurb != null ? blurb.trim() : null);

        } catch (Exception e) {
            log.warn("Failed to parse Chat Completions response: {}", e.getMessage());
            return null;
        }
    }

    private record VotdResult(String reference, String blurb) {}
}
