package com.readthekjv.service;

import com.readthekjv.model.Verse;
import com.readthekjv.model.entity.VerseOfDay;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Posts the daily AI-selected verse to X (Twitter) at noon UTC.
 *
 * <p>Authentication: OAuth 1.0a user context (API Key + Secret + Access Token + Secret).
 * All signing is done with Java stdlib — no additional Maven dependency.
 *
 * <p>Graceful degradation: if credentials are missing or the API call fails,
 * the error is logged and suppressed — never throws.
 */
@Service
public class XPostService {

    private static final Logger log = LoggerFactory.getLogger(XPostService.class);

    private static final String POST_TWEET_URL = "https://api.x.com/2/tweets";

    /** t.co shortens all URLs to exactly 23 characters for counting purposes. */
    private static final int TCO_URL_LENGTH = 23;

    private static final String[] FEATURE_TAGLINES = {
            "Distraction-free. No ads.",
            "Designed like a printed Bible.",
            "Save verses, notes & tags.",
            "Full-text search, 31,102 verses.",
            "Two-column layout. Just the Word.",
            "Desktop-first KJV reading.",
            "Listen with audio narration."
    };

    private final VerseOfDayService verseOfDayService;
    private final BibleService bibleService;
    private final HttpClient httpClient;

    @Value("${x.enabled:true}")
    private boolean enabled;

    @Value("${x.api-key:}")
    private String apiKey;

    @Value("${x.api-secret:}")
    private String apiSecret;

    @Value("${x.access-token:}")
    private String accessToken;

    @Value("${x.access-token-secret:}")
    private String accessTokenSecret;

    @Value("${app.base-url:https://readthekjv.com}")
    private String baseUrl;

    public XPostService(VerseOfDayService verseOfDayService, BibleService bibleService) {
        this.verseOfDayService = verseOfDayService;
        this.bibleService      = bibleService;
        this.httpClient        = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    // ── Scheduling ────────────────────────────────────────────────────────────

    /**
     * Posts today's verse at noon UTC (7 AM Eastern / 4 AM Pacific).
     * Skips silently if credentials are not configured.
     */
    @Scheduled(cron = "0 0 12 * * *", zone = "UTC")
    public void postScheduled() {
        if (!enabled) {
            log.debug("X posting is disabled");
            return;
        }
        if (apiKey.isBlank() || apiSecret.isBlank() ||
                accessToken.isBlank() || accessTokenSecret.isBlank()) {
            log.debug("X credentials not configured — skipping daily post");
            return;
        }

        try {
            Optional<VerseOfDay> votdOpt = verseOfDayService.getTodaysVerse();
            if (votdOpt.isEmpty()) {
                log.warn("No verse-of-day found for today — X post skipped");
                return;
            }
            VerseOfDay votd = votdOpt.get();

            Optional<Verse> verseOpt = bibleService.getVerse(votd.getVerseId());
            if (verseOpt.isEmpty()) {
                log.warn("Could not resolve verseId {} — X post skipped", votd.getVerseId());
                return;
            }
            Verse verse = verseOpt.get();

            String tweetText = buildTweetText(
                    votd.getVerseId(), verse.text(), verse.reference(), votd.getDate());

            postTweet(tweetText);

        } catch (Exception e) {
            log.error("Failed to post verse of the day to X: {}", e.getMessage(), e);
        }
    }

    // ── Tweet text builder ────────────────────────────────────────────────────

    /**
     * Builds the tweet text with budget-aware tagline inclusion.
     *
     * <p>Format:
     * <pre>
     * "{verse text}"
     *
     * — Reference
     *
     * {optional tagline}
     * {url} #KJV #VerseOfTheDay
     * </pre>
     *
     * <p>URL is counted at {@value #TCO_URL_LENGTH} chars (t.co fixed cost).
     */
    String buildTweetText(int verseId, String verseText, String reference, LocalDate date) {
        String url      = baseUrl + "/?vid=" + verseId;
        String tagline  = FEATURE_TAGLINES[date.getDayOfYear() % FEATURE_TAGLINES.length];
        String hashtags = "#KJV #VerseOfTheDay";

        // Build the fixed suffix (URL counts as TCO_URL_LENGTH regardless of actual length)
        String suffixWithTagline    = "\n" + tagline + "\n" + url + " " + hashtags;
        String suffixWithoutTagline = "\n" + url + " " + hashtags;

        // Tweet length: replace actual URL length with t.co cost
        int urlActualLen = url.length();

        // Try with tagline
        String candidate = buildBody(verseText, reference) + suffixWithTagline;
        if (tweetLength(candidate, urlActualLen) <= 280) {
            return candidate;
        }

        // Try without tagline
        candidate = buildBody(verseText, reference) + suffixWithoutTagline;
        if (tweetLength(candidate, urlActualLen) <= 280) {
            return candidate;
        }

        // Truncate verse text to fit without tagline
        int overhead = suffixWithoutTagline.length() - urlActualLen + TCO_URL_LENGTH
                + "\"\"\n\n— ".length() + reference.length() + "\n".length();
        // "\"" + verse + "\"\n\n— " + reference + "\n" + suffix
        // overhead = 2 (quotes) + 4 (\n\n— ) + ref + 1 (\n) + tco-adjusted suffix
        int maxVerseLen = 280 - overhead;
        if (maxVerseLen < 10) maxVerseLen = 10; // always include something
        String truncated = verseText.length() > maxVerseLen
                ? verseText.substring(0, maxVerseLen - 1) + "…"
                : verseText;

        return buildBody(truncated, reference) + suffixWithoutTagline;
    }

    /** Core verse + reference block. */
    private String buildBody(String verseText, String reference) {
        return "\"" + verseText + "\"\n\n— " + reference + "\n";
    }

    /** Computes tweet length treating the URL at its t.co cost. */
    private int tweetLength(String text, int actualUrlLen) {
        return text.length() - actualUrlLen + TCO_URL_LENGTH;
    }

    // ── X API call ────────────────────────────────────────────────────────────

    private void postTweet(String text) throws Exception {
        // Escape text for JSON manually to avoid pulling in extra logic
        String jsonBody = "{\"text\":" + jsonStringEscape(text) + "}";

        String authHeader = buildOAuthHeader("POST", POST_TWEET_URL);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(POST_TWEET_URL))
                .header("Authorization",  authHeader)
                .header("Content-Type",   "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 201) {
            log.info("Posted verse of the day to X successfully");
        } else {
            log.error("X API error {}: {}", response.statusCode(), response.body());
        }
    }

    // ── OAuth 1.0a signing ────────────────────────────────────────────────────

    /**
     * Builds an OAuth 1.0a {@code Authorization} header for the given request.
     *
     * <p>For JSON-body requests, only the OAuth parameters are signed
     * (form-encoded body parameters are not applicable).
     */
    private String buildOAuthHeader(String method, String url) {
        String nonce     = UUID.randomUUID().toString().replace("-", "");
        String timestamp = String.valueOf(Instant.now().getEpochSecond());

        // Collect and sort OAuth parameters
        TreeMap<String, String> oauthParams = new TreeMap<>();
        oauthParams.put("oauth_consumer_key",     apiKey);
        oauthParams.put("oauth_nonce",            nonce);
        oauthParams.put("oauth_signature_method", "HMAC-SHA1");
        oauthParams.put("oauth_timestamp",        timestamp);
        oauthParams.put("oauth_token",            accessToken);
        oauthParams.put("oauth_version",          "1.0");

        // Build percent-encoded parameter string (sorted key=value&... )
        StringBuilder paramSb = new StringBuilder();
        for (var entry : oauthParams.entrySet()) {
            if (paramSb.length() > 0) paramSb.append('&');
            paramSb.append(pct(entry.getKey())).append('=').append(pct(entry.getValue()));
        }

        // Build signature base string
        String baseString = method.toUpperCase()
                + "&" + pct(url)
                + "&" + pct(paramSb.toString());

        // Signing key
        String signingKey = pct(apiSecret) + "&" + pct(accessTokenSecret);

        // HMAC-SHA1
        String signature = hmacSha1(signingKey, baseString);
        oauthParams.put("oauth_signature", signature);

        // Build Authorization header value
        StringBuilder header = new StringBuilder("OAuth ");
        boolean first = true;
        for (var entry : oauthParams.entrySet()) {
            if (!first) header.append(", ");
            header.append(pct(entry.getKey())).append("=\"").append(pct(entry.getValue())).append('"');
            first = false;
        }
        return header.toString();
    }

    /** RFC 3986 percent-encoding (space→%20, *→%2A, ~→~). */
    private String pct(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+",   "%20")
                .replace("*",   "%2A")
                .replace("%7E", "~");
    }

    /** HMAC-SHA1 → Base64. */
    private String hmacSha1(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("OAuth signing failed", e);
        }
    }

    /** Minimal JSON string escaping for tweet text. */
    private String jsonStringEscape(String s) {
        return "\"" + s
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                + "\"";
    }
}
