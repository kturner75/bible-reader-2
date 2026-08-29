package com.readthekjv.service;

import com.readthekjv.model.Verse;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * Text-to-speech audio generation via a configurable provider (OpenAI or xAI).
 * Stores audio files in Digital Ocean Spaces with CDN delivery.
 *
 * <p>Set {@code TTS_PROVIDER=xai} and {@code XAI_API_KEY} to switch providers.
 * {@code TTS_VOICE} is passed through as the xAI {@code voice_id} (case-insensitive);
 * there is no closed voice enum. Unset defaults to {@code onyx} / {@code eve}.
 * An unknown xAI id 404s that generate and is not written to Spaces.
 * An unset {@code tts.provider} defaults to openai; any other value that is not
 * {@code openai} or {@code xai} fails closed (no spend).
 *
 * <p>H2: cache lookups are cheap; generation is concurrency-capped and
 * must only be invoked from an authenticated controller path.
 */
@Service
public class TtsService {

    private static final Logger log = LoggerFactory.getLogger(TtsService.class);

    /** Max simultaneous TTS HTTP calls (request + prefetch share this). */
    private static final int MAX_CONCURRENT_GENERATIONS = 2;

    private static final String OPENAI_TTS_URL = "https://api.openai.com/v1/audio/speech";
    private static final String XAI_TTS_URL = "https://api.x.ai/v1/tts";
    private static final String OPENAI_DEFAULT_VOICE = "onyx";
    private static final String XAI_DEFAULT_VOICE = "eve";
    private static final String OPENAI_DEFAULT_MODEL = "tts-1-hd";

    @Value("${tts.enabled:false}")
    private boolean enabled;

    @Value("${tts.provider:openai}")
    private String provider;

    @Value("${tts.api-key:}")
    private String apiKey;

    @Value("${XAI_API_KEY:}")
    private String xaiKey;

    @Value("${tts.voice:}")
    private String voice;

    @Value("${tts.model:tts-1-hd}")
    private String model;

    @Value("${tts.prefetch-count:10}")
    private int prefetchCount;

    @Value("${spaces.enabled:false}")
    private boolean spacesEnabled;

    @Value("${spaces.endpoint:}")
    private String spacesEndpoint;

    @Value("${spaces.bucket:}")
    private String spacesBucket;

    @Value("${spaces.cdn-url:}")
    private String spacesCdnUrl;

    @Value("${spaces.access-key:}")
    private String spacesAccessKey;

    @Value("${spaces.secret-key:}")
    private String spacesSecretKey;

    @Value("${spaces.audio-prefix:audio}")
    private String audioPrefix;

    private final BibleService bibleService;
    private HttpClient httpClient;
    private final Semaphore generationPermits = new Semaphore(MAX_CONCURRENT_GENERATIONS);
    private S3Client s3Client;
    private ExecutorService prefetchExecutor;

    public TtsService(BibleService bibleService) {
        this.bibleService = bibleService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @PostConstruct
    public void init() {
        if (!isEnabled()) {
            log.info("TTS service is disabled");
            return;
        }

        // Initialize S3 client for Digital Ocean Spaces
        if (spacesEnabled && !spacesAccessKey.isBlank() && !spacesSecretKey.isBlank()) {
            try {
                s3Client = S3Client.builder()
                        .endpointOverride(URI.create(spacesEndpoint))
                        .region(Region.of("nyc3"))
                        .credentialsProvider(StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(spacesAccessKey, spacesSecretKey)))
                        .build();
                log.info("TTS service initialized - provider: {}, voice: {}, model: {}, spaces: {}, cdn: {}",
                        providerKeySegment(), resolvedVoice(),
                        isXai() ? "-" : resolvedModel(), spacesBucket, spacesCdnUrl);
            } catch (Exception e) {
                log.error("Failed to initialize S3 client for Spaces", e);
                s3Client = null;
            }
        } else {
            log.warn("TTS service: Spaces not configured, audio generation disabled");
        }

        // Initialize prefetch executor
        prefetchExecutor = Executors.newFixedThreadPool(2);
    }

    @PreDestroy
    public void shutdown() {
        if (prefetchExecutor != null) {
            prefetchExecutor.shutdown();
        }
        if (s3Client != null) {
            s3Client.close();
        }
    }

    /**
     * Returns true if TTS is enabled, the provider is known, and the
     * matching API key is present. Unknown providers fail closed.
     */
    public boolean isEnabled() {
        String key = resolvedKey();
        return enabled && isKnownProvider() && key != null && !key.isBlank();
    }

    /**
     * Returns the CDN URL if the verse audio already exists in Spaces.
     * Does not call a TTS provider and does not prefetch.
     */
    public Optional<String> findCachedAudioUrlForVerse(int verseId) {
        if (!isEnabled() || s3Client == null) {
            return Optional.empty();
        }
        return findExistingCdnUrl(verseCacheKeys(verseId));
    }

    /**
     * Returns the CDN URL if the chapter announcement already exists in Spaces.
     * Does not call a TTS provider.
     */
    public Optional<String> findCachedAudioUrlForChapter(String book, int chapter) {
        if (!isEnabled() || s3Client == null) {
            return Optional.empty();
        }
        return findExistingCdnUrl(chapterCacheKeys(book, chapter));
    }

    /**
     * Gets the CDN URL for a verse audio, generating it if not already in Spaces.
     * Also triggers background prefetch of upcoming verses.
     *
     * <p>Caller must enforce authentication — this method spends TTS budget.
     *
     * @param verseId The verse ID (1-31102)
     * @return Optional containing the CDN URL, or empty if unavailable
     */
    public Optional<String> getAudioUrlForVerse(int verseId) {
        if (!isEnabled() || s3Client == null) {
            return Optional.empty();
        }

        Optional<String> cached = findExistingCdnUrl(verseCacheKeys(verseId));
        if (cached.isPresent()) {
            triggerPrefetch(verseId);
            return cached;
        }

        Optional<Verse> verseOpt = bibleService.getVerse(verseId);
        if (verseOpt.isEmpty()) {
            log.warn("Verse not found: {}", verseId);
            return Optional.empty();
        }

        Verse verse = verseOpt.get();
        String speechText = formatVerseForSpeech(verse);

        try {
            byte[] audioData = callTts(speechText);
            if (audioData == null || audioData.length == 0) {
                return Optional.empty();
            }

            String key = getVerseKey(verseId);
            uploadToSpaces(key, audioData);
            log.info("Generated and uploaded verse {}: {}", verseId, key);

            triggerPrefetch(verseId);

            return Optional.of(getCdnUrl(key));

        } catch (IOException | InterruptedException e) {
            log.error("Failed to generate audio for verse {}", verseId, e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        }
    }

    /**
     * Gets the CDN URL for a chapter announcement audio.
     *
     * <p>Caller must enforce authentication — this method spends TTS budget.
     *
     * @param book The book name (canonical Bible book name)
     * @param chapter The chapter number
     * @return Optional containing the CDN URL, or empty if unavailable
     */
    public Optional<String> getAudioUrlForChapter(String book, int chapter) {
        if (!isEnabled() || s3Client == null) {
            return Optional.empty();
        }

        Optional<String> cached = findExistingCdnUrl(chapterCacheKeys(book, chapter));
        if (cached.isPresent()) {
            return cached;
        }

        String speechText = formatChapterForSpeech(book, chapter);

        try {
            byte[] audioData = callTts(speechText);
            if (audioData == null || audioData.length == 0) {
                return Optional.empty();
            }

            String key = getChapterKey(book, chapter);
            uploadToSpaces(key, audioData);
            log.info("Generated and uploaded chapter {} {}: {}", book, chapter, key);

            return Optional.of(getCdnUrl(key));

        } catch (IOException | InterruptedException e) {
            log.error("Failed to generate audio for chapter {} {}", book, chapter, e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        }
    }

    /**
     * True when {@code book} is a known canonical Bible book name.
     */
    public boolean isKnownBook(String book) {
        return book != null && bibleService.getBookByName(book).isPresent();
    }

    /**
     * Triggers background prefetch of upcoming verses.
     * Only call for authenticated requests (amplifies TTS spend).
     */
    public void triggerPrefetch(int currentVerseId) {
        if (prefetchExecutor == null) return;

        prefetchExecutor.submit(() -> {
            int totalVerses = bibleService.getTotalVerses();
            for (int i = 1; i <= prefetchCount; i++) {
                int verseId = currentVerseId + i;
                if (verseId > totalVerses) break;

                if (findExistingCdnUrl(verseCacheKeys(verseId)).isPresent()) {
                    continue;
                }
                try {
                    Optional<Verse> verseOpt = bibleService.getVerse(verseId);
                    if (verseOpt.isPresent()) {
                        String speechText = formatVerseForSpeech(verseOpt.get());
                        byte[] audioData = callTts(speechText);
                        if (audioData != null && audioData.length > 0) {
                            String key = getVerseKey(verseId);
                            uploadToSpaces(key, audioData);
                            log.debug("Prefetched verse {}", verseId);
                        }
                    }
                } catch (Exception e) {
                    log.debug("Prefetch failed for verse {}: {}", verseId, e.getMessage());
                }
            }
        });
    }

    // ── Provider resolution (mirrors VerseOfDayService / WhisperService) ──────

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
        return isXai() ? XAI_TTS_URL : OPENAI_TTS_URL;
    }

    String resolvedKey() {
        return isXai() ? xaiKey : apiKey;
    }

    /**
     * Configured voice, or the provider default when {@code TTS_VOICE} is unset.
     * xAI ids are case-insensitive ({@code ARA} → {@code ara}); any string is
     * accepted — no allowlist. Unknown ids fail at generate time (xAI 404).
     */
    String resolvedVoice() {
        if (voice != null && !voice.isBlank()) {
            String trimmed = voice.trim();
            return isXai() ? trimmed.toLowerCase(Locale.ROOT) : trimmed;
        }
        return isXai() ? XAI_DEFAULT_VOICE : OPENAI_DEFAULT_VOICE;
    }

    String resolvedModel() {
        if (model != null && !model.isBlank()) {
            return model;
        }
        return OPENAI_DEFAULT_MODEL;
    }

    /**
     * OpenAI keeps reading the unversioned {@code audio/verses/…} layout so the
     * existing onyx cache still hits. xAI (or a non-onyx OpenAI voice) never
     * consults those keys — flipping provider/voice must not serve the old files.
     */
    boolean usesLegacyCache() {
        return !isXai() && OPENAI_DEFAULT_VOICE.equalsIgnoreCase(resolvedVoice());
    }

    // ── Spaces key layout ─────────────────────────────────────────────────────

    /**
     * Canonical namespaced key: {@code audio/{provider}/{voice}/verses/{bucket}/{id}.mp3}.
     */
    String getVerseKey(int verseId) {
        int bucket = verseId / 1000;
        return audioPrefix + "/" + providerKeySegment() + "/" + voiceKeySegment()
                + "/verses/" + bucket + "/" + verseId + ".mp3";
    }

    /**
     * Canonical namespaced key: {@code audio/{provider}/{voice}/chapters/{book}_{chapter}.mp3}.
     */
    String getChapterKey(String book, int chapter) {
        String safeBookName = book.replace(" ", "_");
        return audioPrefix + "/" + providerKeySegment() + "/" + voiceKeySegment()
                + "/chapters/" + safeBookName + "_" + chapter + ".mp3";
    }

    /**
     * Single S3 path segment for the resolved voice. Neutralizes {@code /},
     * {@code ..}, backslashes, and other path tricks so {@code TTS_VOICE}
     * cannot escape {@code audio/{provider}/{voice}/}. The TTS request still
     * receives {@link #resolvedVoice()} as a pass-through (any string).
     */
    String voiceKeySegment() {
        String raw = resolvedVoice();
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        String segment = sb.toString();
        return segment.isBlank() ? "_" : segment;
    }

    String getLegacyVerseKey(int verseId) {
        int bucket = verseId / 1000;
        return audioPrefix + "/verses/" + bucket + "/" + verseId + ".mp3";
    }

    String getLegacyChapterKey(String book, int chapter) {
        String safeBookName = book.replace(" ", "_");
        return audioPrefix + "/chapters/" + safeBookName + "_" + chapter + ".mp3";
    }

    /**
     * Lookup order: namespaced key first, then the unversioned openai/onyx key
     * when {@link #usesLegacyCache()} is true. Writes always use the namespaced key.
     */
    List<String> verseCacheKeys(int verseId) {
        List<String> keys = new ArrayList<>(2);
        keys.add(getVerseKey(verseId));
        if (usesLegacyCache()) {
            keys.add(getLegacyVerseKey(verseId));
        }
        return keys;
    }

    List<String> chapterCacheKeys(String book, int chapter) {
        List<String> keys = new ArrayList<>(2);
        keys.add(getChapterKey(book, chapter));
        if (usesLegacyCache()) {
            keys.add(getLegacyChapterKey(book, chapter));
        }
        return keys;
    }

    private String providerKeySegment() {
        return isXai() ? "xai" : "openai";
    }

    private String getCdnUrl(String key) {
        return spacesCdnUrl + "/" + key;
    }

    private Optional<String> findExistingCdnUrl(List<String> keys) {
        for (String key : keys) {
            if (existsInSpaces(key)) {
                return Optional.of(getCdnUrl(key));
            }
        }
        return Optional.empty();
    }

    private boolean existsInSpaces(String key) {
        try {
            s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(spacesBucket)
                    .key(key)
                    .build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (Exception e) {
            log.warn("Error checking Spaces for key {}: {}", key, e.getMessage());
            return false;
        }
    }

    private void uploadToSpaces(String key, byte[] data) {
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(spacesBucket)
                        .key(key)
                        .contentType("audio/mpeg")
                        .acl("public-read")
                        .build(),
                RequestBody.fromBytes(data));
    }

    /**
     * Formats a verse for speech output.
     */
    String formatVerseForSpeech(Verse verse) {
        return verse.text();
    }

    /**
     * Formats a chapter announcement for speech output.
     */
    String formatChapterForSpeech(String book, int chapter) {
        boolean isPsalm = "Psalm".equals(book) || "Psalms".equals(book);
        String announcement = isPsalm ? "Psalm " + chapter : "Chapter " + chapter;
        return "... " + announcement + " ...";
    }

    /**
     * Builds the provider-specific TTS JSON body.
     * OpenAI keeps {@code model/input/voice/response_format=mp3}.
     * xAI sends {@code text/voice_id/language/output_format} and no model field.
     */
    String buildTtsRequestBody(String text) {
        if (isXai()) {
            return "{\"text\": " + escapeJson(text)
                    + ", \"voice_id\": " + escapeJson(resolvedVoice())
                    + ", \"language\": \"en\""
                    + ", \"output_format\": {\"codec\": \"mp3\"}}";
        }
        return "{\"model\": " + escapeJson(resolvedModel())
                + ", \"input\": " + escapeJson(text)
                + ", \"voice\": " + escapeJson(resolvedVoice())
                + ", \"response_format\": \"mp3\"}";
    }

    /**
     * Calls the configured TTS API to generate audio. Concurrency-capped (H2).
     * Unknown providers fail closed — no HTTP, no spend.
     */
    private byte[] callTts(String text) throws IOException, InterruptedException {
        if (!isKnownProvider()) {
            log.warn("Unknown tts.provider '{}' — expected openai or xai; skipping TTS generation",
                    provider);
            return null;
        }
        generationPermits.acquire();
        try {
            String requestBody = buildTtsRequestBody(text);

            log.debug("TTS provider={} url={} voice={}", provider, resolvedUrl(), resolvedVoice());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(resolvedUrl()))
                    .header("Authorization", "Bearer " + resolvedKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() != 200) {
                if (response.statusCode() == 404) {
                    log.warn("TTS API 404 for voice '{}' — skipping generate, not persisting",
                            resolvedVoice());
                } else {
                    log.error("TTS API error: {} - {}", response.statusCode(), new String(response.body()));
                }
                return null;
            }

            return response.body();
        } finally {
            generationPermits.release();
        }
    }

    /**
     * Escapes a string for JSON.
     */
    private String escapeJson(String text) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 32) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append("\"");
        return sb.toString();
    }
}
