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
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * Text-to-speech audio generation via a configurable provider (OpenAI or xAI).
 * Stores audio files in Digital Ocean Spaces with CDN delivery.
 *
 * <p>Set {@code TTS_PROVIDER=xai} to switch providers. The bearer is a SuperGrok
 * OAuth access token when {@link XaiOAuthTokenManager} has one (subscription
 * quota), falling back to {@code XAI_API_KEY} (pay-per-token).
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

    /**
     * Max simultaneous TTS HTTP calls (request + prefetch share this). Serving wants
     * this low; a bulk pregeneration run wants it high, hence the override.
     */
    private static final int DEFAULT_MAX_CONCURRENT_GENERATIONS = 2;

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

    /**
     * Read at construction, not injected: {@link #generationPermits} is a final field
     * initialised before Spring can populate an {@code @Value}. A bulk run raises it
     * with {@code -Dtts.max-concurrent-generations=8}.
     */
    private static int maxConcurrentGenerations() {
        String raw = System.getProperty("tts.max-concurrent-generations",
                System.getenv("TTS_MAX_CONCURRENT_GENERATIONS"));
        if (raw == null || raw.isBlank()) {
            return DEFAULT_MAX_CONCURRENT_GENERATIONS;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed > 0 ? parsed : DEFAULT_MAX_CONCURRENT_GENERATIONS;
        } catch (NumberFormatException e) {
            log.warn("Bad tts.max-concurrent-generations '{}' — using {}", raw,
                    DEFAULT_MAX_CONCURRENT_GENERATIONS);
            return DEFAULT_MAX_CONCURRENT_GENERATIONS;
        }
    }

    private final BibleService bibleService;
    private final XaiOAuthTokenManager xaiOAuthTokenManager;
    private HttpClient httpClient;
    private final Semaphore generationPermits = new Semaphore(maxConcurrentGenerations());
    private S3Client s3Client;
    private ExecutorService prefetchExecutor;

    public TtsService(BibleService bibleService, XaiOAuthTokenManager xaiOAuthTokenManager) {
        this.bibleService = bibleService;
        this.xaiOAuthTokenManager = xaiOAuthTokenManager;
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
        if (!enabled || !isKnownProvider()) {
            return false;
        }
        // isConfigured() is a local check — never refresh a token just to report availability.
        if (isXai() && xaiOAuthTokenManager != null && xaiOAuthTokenManager.isConfigured()) {
            return true;
        }
        String key = resolvedKey();
        return key != null && !key.isBlank();
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

    // ── Voice-scoped lookups (serve-only; see VoiceCatalogService) ───────────

    /**
     * Cache lookup in a caller-chosen voice. Serve-only by construction: there is
     * no generating counterpart, because a selectable voice is one whose corpus is
     * already complete. That is what keeps a user-supplied voice from becoming an
     * arbitrary spend vector — the request can only ever hit or miss, never spend.
     */
    public Optional<String> findCachedAudioUrlForVerse(int verseId, String voice) {
        if (!isEnabled() || s3Client == null) {
            return Optional.empty();
        }
        return findExistingCdnUrl(verseCacheKeys(verseId, voice));
    }

    public Optional<String> findCachedAudioUrlForChapter(String book, int chapter, String voice) {
        if (!isEnabled() || s3Client == null) {
            return Optional.empty();
        }
        return findExistingCdnUrl(chapterCacheKeys(book, chapter, voice));
    }

    public Optional<String> findCachedAudioUrlForBook(String book, String voice) {
        if (!isEnabled() || s3Client == null) {
            return Optional.empty();
        }
        return findExistingCdnUrl(bookCacheKeys(book, voice));
    }

    /**
     * True when generated audio can actually be stored. Generation without a
     * writable bucket spends TTS quota and throws the result away.
     */
    public boolean isSpacesReady() {
        return s3Client != null;
    }

    /** The voice this server generates in when a request does not name one. */
    public String defaultVoice() {
        return resolvedVoice();
    }

    /** Every key under the audio prefix, grouped for the catalog's completeness count. */
    public Set<String> allAudioKeys() {
        return listExistingKeys();
    }

    /** The bearer a metadata call (the xAI voice roster) should use. */
    String metadataBearer() {
        // xAI-only by construction. resolvedBearer() hands back OPENAI_API_KEY under
        // the openai provider, and this bearer is sent to api.x.ai — disclosing one
        // provider's credential to another. No provider match, no bearer.
        return isXai() ? resolvedBearer() : null;
    }

    String providerSegment() {
        return providerKeySegment();
    }

    String audioPrefixValue() {
        return audioPrefix;
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
     * Returns the CDN URL if the book announcement already exists in Spaces.
     * Does not call a TTS provider.
     */
    public Optional<String> findCachedAudioUrlForBook(String book) {
        if (!isEnabled() || s3Client == null) {
            return Optional.empty();
        }
        return findExistingCdnUrl(bookCacheKeys(book));
    }

    /**
     * Gets the CDN URL for a book announcement audio, spoken at a book break
     * before the chapter announcement.
     *
     * <p>Caller must enforce authentication — this method spends TTS budget.
     *
     * @param book The book name (canonical Bible book name)
     * @return Optional containing the CDN URL, or empty if unavailable
     */
    public Optional<String> getAudioUrlForBook(String book) {
        if (!isEnabled() || s3Client == null) {
            return Optional.empty();
        }

        Optional<String> cached = findExistingCdnUrl(bookCacheKeys(book));
        if (cached.isPresent()) {
            return cached;
        }

        String speechText = formatBookForSpeech(book);

        try {
            byte[] audioData = callTts(speechText);
            if (audioData == null || audioData.length == 0) {
                return Optional.empty();
            }

            String key = getBookKey(book);
            uploadToSpaces(key, audioData);
            log.info("Generated and uploaded book {}: {}", book, key);

            return Optional.of(getCdnUrl(key));

        } catch (IOException | InterruptedException e) {
            log.error("Failed to generate audio for book {}", book, e);
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

    // ── Bulk pregeneration seams (see TtsPregenService) ──────────────────────

    /**
     * Every key already in Spaces under the audio prefix. One paginated LIST beats
     * tens of thousands of HEADs, and it covers the legacy layout too, so a pregen
     * run skips anything an earlier provider/voice already produced.
     */
    Set<String> listExistingKeys() {
        Set<String> keys = new HashSet<>();
        if (s3Client == null) {
            log.warn("Spaces is not configured — cannot list existing audio. Every clip will look "
                    + "missing; treat any gap count from this run as an upper bound.");
            return keys;
        }
        String token = null;
        do {
            ListObjectsV2Response page = s3Client.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(spacesBucket)
                    .prefix(audioPrefix + "/")
                    .continuationToken(token)
                    .build());
            page.contents().forEach(o -> keys.add(o.key()));
            token = Boolean.TRUE.equals(page.isTruncated()) ? page.nextContinuationToken() : null;
        } while (token != null);
        return keys;
    }

    /**
     * Generates one clip and uploads it to {@code key}. Returns false when the
     * provider declined (unknown voice, error) — the caller counts it as skipped
     * rather than retrying, so one bad clip cannot stall a 31k-item run.
     *
     * <p>Always {@link BearerPolicy#OAUTH_ONLY}, with no override: this is the bulk
     * path, and the whole point is that an exhausted subscription stops the run
     * instead of silently moving tens of thousands of clips onto a metered key.
     * An {@link AuthUnavailableException} here means abort, not skip.
     */
    boolean generateAndUpload(String key, String speechText)
            throws IOException, InterruptedException {
        byte[] audioData = callTts(speechText, BearerPolicy.OAUTH_ONLY);
        if (audioData == null || audioData.length == 0) {
            return false;
        }
        uploadToSpaces(key, audioData);
        return true;
    }

    /** The namespace a pregen run writes into, for logging and confirmation. */
    String currentNamespace() {
        return audioPrefix + "/" + providerKeySegment() + "/" + voiceKeySegment();
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
     * Which credentials a call is willing to bill.
     *
     * <p>The two differ in blast radius, not in mechanism. Serving generates one clip
     * a reader is waiting on, so falling back to the metered key is the right trade.
     * A bulk run generates tens of thousands unattended, where the same fallback turns
     * an expired subscription token into a large, silent invoice — so it is refused.
     */
    enum BearerPolicy {
        /** Serving path: OAuth if available, else the metered API key. */
        ALLOW_API_KEY,
        /** Bulk path: subscription OAuth only. No token, no generation. */
        OAUTH_ONLY
    }

    /**
     * Thrown instead of billing a metered key under {@link BearerPolicy#OAUTH_ONLY}.
     * Distinct from a clip that merely failed: the caller must stop the whole run,
     * not count one skip and continue into thousands more.
     */
    static class AuthUnavailableException extends IOException {
        AuthUnavailableException(String message) {
            super(message);
        }
    }

    /**
     * Bearer for the TTS call: SuperGrok OAuth access token when xAI and present,
     * otherwise the static provider API key. Matches {@code VerseOfDayService} and
     * {@code WhisperService} — an OAuth token draws on subscription quota, the
     * static {@code XAI_API_KEY} bills per token.
     */
    String resolvedBearer() {
        return resolvedBearer(BearerPolicy.ALLOW_API_KEY);
    }

    /**
     * Under {@link BearerPolicy#OAUTH_ONLY} this returns null rather than the API
     * key — null means "do not generate", never "generate on the metered key".
     */
    String resolvedBearer(BearerPolicy policy) {
        if (isXai() && xaiOAuthTokenManager != null) {
            Optional<String> oauth = xaiOAuthTokenManager.getAccessToken();
            if (oauth.isPresent() && !oauth.get().isBlank()) {
                return oauth.get();
            }
        }
        return policy == BearerPolicy.OAUTH_ONLY ? null : resolvedKey();
    }

    /**
     * True when a SuperGrok OAuth access token can be minted right now. Used as a
     * pre-flight so a bulk run fails at startup instead of discovering it clip by
     * clip. This does mint a token (unlike {@code isConfigured()}), which is the point.
     */
    boolean hasOAuthBearer() {
        String bearer = resolvedBearer(BearerPolicy.OAUTH_ONLY);
        return bearer != null && !bearer.isBlank();
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

    // ── Spaces key layout ─────────────────────────────────────────────────────

    /**
     * Canonical namespaced key: {@code audio/{provider}/{voice}/verses/{bucket}/{id}.mp3}.
     */
    String getVerseKey(int verseId) {
        return getVerseKey(verseId, resolvedVoice());
    }

    String getVerseKey(int verseId, String voice) {
        int bucket = verseId / 1000;
        return audioPrefix + "/" + providerKeySegment() + "/" + voiceKeySegment(voice)
                + "/verses/" + bucket + "/" + verseId + ".mp3";
    }

    /**
     * Canonical namespaced key: {@code audio/{provider}/{voice}/chapters/{chapter}.mp3},
     * or {@code .../chapters/psalm_{chapter}.mp3} in the Psalms.
     *
     * <p>The clip says "Chapter 3" and names no book, so one file serves every
     * book that has a chapter 3 — 150 objects, not 1,189. The key must therefore
     * vary with exactly what {@link #formatChapterForSpeech} speaks and nothing
     * else, which is why the Psalms ("Psalm 3") get their own prefix.
     */
    String getChapterKey(String book, int chapter) {
        return getChapterKey(book, chapter, resolvedVoice());
    }

    String getChapterKey(String book, int chapter, String voice) {
        return audioPrefix + "/" + providerKeySegment() + "/" + voiceKeySegment(voice)
                + "/chapters/" + (isPsalmBook(book) ? "psalm_" : "") + chapter + ".mp3";
    }

    /**
     * Canonical namespaced key: {@code audio/{provider}/{voice}/books/{book}.mp3}.
     *
     * <p>Book announcements postdate the unversioned {@code audio/…} layout, so
     * there is no legacy key to fall back to — see {@link #bookCacheKeys(String)}.
     */
    String getBookKey(String book) {
        return getBookKey(book, resolvedVoice());
    }

    String getBookKey(String book, String voice) {
        String safeBookName = book.replace(" ", "_");
        return audioPrefix + "/" + providerKeySegment() + "/" + voiceKeySegment(voice)
                + "/books/" + safeBookName + ".mp3";
    }

    /**
     * Single S3 path segment for the resolved voice. Neutralizes {@code /},
     * {@code ..}, backslashes, and other path tricks so {@code TTS_VOICE}
     * cannot escape {@code audio/{provider}/{voice}/}. The TTS request still
     * receives {@link #resolvedVoice()} as a pass-through (any string).
     */
    String voiceKeySegment() {
        return voiceKeySegment(resolvedVoice());
    }

    String voiceKeySegment(String raw) {
        if (raw == null) {
            return "_";
        }
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

    /**
     * The unversioned {@code audio/verses/…} layout is gone — those 3,329 openai/onyx
     * objects were deleted once the xai/helios corpus was complete, so there is no
     * longer any fallback for any of the three key families.
     */
    List<String> verseCacheKeys(int verseId) {
        return verseCacheKeys(verseId, resolvedVoice());
    }

    List<String> verseCacheKeys(int verseId, String voice) {
        return List.of(getVerseKey(verseId, voice));
    }

    /**
     * No legacy fallback: the unversioned layout stored a separate clip per book
     * ({@code audio/chapters/Genesis_1.mp3}), so consulting it would keep serving
     * the duplicates this key layout exists to collapse. Those objects are simply
     * orphaned; the shared clip is regenerated once per chapter number.
     */
    List<String> chapterCacheKeys(String book, int chapter) {
        return List.of(getChapterKey(book, chapter));
    }

    List<String> chapterCacheKeys(String book, int chapter, String voice) {
        return List.of(getChapterKey(book, chapter, voice));
    }

    /**
     * Book announcements have only ever been written to the namespaced layout,
     * so unlike verses and chapters there is no legacy key to consult.
     */
    List<String> bookCacheKeys(String book) {
        return List.of(getBookKey(book));
    }

    List<String> bookCacheKeys(String book, String voice) {
        return List.of(getBookKey(book, voice));
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
     * The Authorized Version's own book headings, spoken at a book break —
     * "The Second Book of Moses, called Exodus" rather than a bare "Exodus".
     * The twelve minor prophets carry only their name in the AV, so they do here.
     *
     * <p>Keyed by the canonical name in {@code kjv.json} (note "Psalm", singular).
     * {@link #getBookKey} derives the cache key from that name, not from the
     * title, so revising a title here strands the object already in Spaces —
     * delete it, or the old wording keeps playing.
     */
    static final Map<String, String> KJV_BOOK_TITLES = Map.ofEntries(
            Map.entry("Genesis", "The First Book of Moses, called Genesis"),
            Map.entry("Exodus", "The Second Book of Moses, called Exodus"),
            Map.entry("Leviticus", "The Third Book of Moses, called Leviticus"),
            Map.entry("Numbers", "The Fourth Book of Moses, called Numbers"),
            Map.entry("Deuteronomy", "The Fifth Book of Moses, called Deuteronomy"),
            Map.entry("Joshua", "The Book of Joshua"),
            Map.entry("Judges", "The Book of Judges"),
            Map.entry("Ruth", "The Book of Ruth"),
            Map.entry("1 Samuel", "The First Book of Samuel"),
            Map.entry("2 Samuel", "The Second Book of Samuel"),
            Map.entry("1 Kings", "The First Book of the Kings"),
            Map.entry("2 Kings", "The Second Book of the Kings"),
            Map.entry("1 Chronicles", "The First Book of the Chronicles"),
            Map.entry("2 Chronicles", "The Second Book of the Chronicles"),
            Map.entry("Ezra", "The Book of Ezra"),
            Map.entry("Nehemiah", "The Book of Nehemiah"),
            Map.entry("Esther", "The Book of Esther"),
            Map.entry("Job", "The Book of Job"),
            Map.entry("Psalm", "The Book of Psalms"),
            Map.entry("Proverbs", "The Proverbs"),
            Map.entry("Ecclesiastes", "Ecclesiastes, or, The Preacher"),
            Map.entry("Song of Solomon", "The Song of Solomon"),
            Map.entry("Isaiah", "The Book of the Prophet Isaiah"),
            Map.entry("Jeremiah", "The Book of the Prophet Jeremiah"),
            Map.entry("Lamentations", "The Lamentations of Jeremiah"),
            Map.entry("Ezekiel", "The Book of the Prophet Ezekiel"),
            Map.entry("Daniel", "The Book of Daniel"),
            Map.entry("Hosea", "Hosea"),
            Map.entry("Joel", "Joel"),
            Map.entry("Amos", "Amos"),
            Map.entry("Obadiah", "Obadiah"),
            Map.entry("Jonah", "Jonah"),
            Map.entry("Micah", "Micah"),
            Map.entry("Nahum", "Nahum"),
            Map.entry("Habakkuk", "Habakkuk"),
            Map.entry("Zephaniah", "Zephaniah"),
            Map.entry("Haggai", "Haggai"),
            Map.entry("Zechariah", "Zechariah"),
            Map.entry("Malachi", "Malachi"),
            Map.entry("Matthew", "The Gospel according to Saint Matthew"),
            Map.entry("Mark", "The Gospel according to Saint Mark"),
            Map.entry("Luke", "The Gospel according to Saint Luke"),
            Map.entry("John", "The Gospel according to Saint John"),
            Map.entry("Acts", "The Acts of the Apostles"),
            Map.entry("Romans", "The Epistle of Paul the Apostle to the Romans"),
            Map.entry("1 Corinthians", "The First Epistle of Paul the Apostle to the Corinthians"),
            Map.entry("2 Corinthians", "The Second Epistle of Paul the Apostle to the Corinthians"),
            Map.entry("Galatians", "The Epistle of Paul the Apostle to the Galatians"),
            Map.entry("Ephesians", "The Epistle of Paul the Apostle to the Ephesians"),
            Map.entry("Philippians", "The Epistle of Paul the Apostle to the Philippians"),
            Map.entry("Colossians", "The Epistle of Paul the Apostle to the Colossians"),
            Map.entry("1 Thessalonians", "The First Epistle of Paul the Apostle to the Thessalonians"),
            Map.entry("2 Thessalonians", "The Second Epistle of Paul the Apostle to the Thessalonians"),
            Map.entry("1 Timothy", "The First Epistle of Paul the Apostle to Timothy"),
            Map.entry("2 Timothy", "The Second Epistle of Paul the Apostle to Timothy"),
            Map.entry("Titus", "The Epistle of Paul to Titus"),
            Map.entry("Philemon", "The Epistle of Paul to Philemon"),
            Map.entry("Hebrews", "The Epistle of Paul the Apostle to the Hebrews"),
            Map.entry("James", "The General Epistle of James"),
            Map.entry("1 Peter", "The First Epistle General of Peter"),
            Map.entry("2 Peter", "The Second Epistle General of Peter"),
            Map.entry("1 John", "The First Epistle General of John"),
            Map.entry("2 John", "The Second Epistle General of John"),
            Map.entry("3 John", "The Third Epistle General of John"),
            Map.entry("Jude", "The General Epistle of Jude"),
            Map.entry("Revelation", "The Revelation of Saint John the Divine"));

    /**
     * Formats a book announcement for speech output. Falls back to the plain book
     * name if the title table somehow misses — an announcement is worth degrading,
     * never worth failing the request over.
     */
    String formatBookForSpeech(String book) {
        return "... " + KJV_BOOK_TITLES.getOrDefault(book, book) + " ...";
    }

    /**
     * Formats a chapter announcement for speech output.
     */
    String formatChapterForSpeech(String book, int chapter) {
        String announcement = isPsalmBook(book) ? "Psalm " + chapter : "Chapter " + chapter;
        return "... " + announcement + " ...";
    }

    /**
     * The Psalms are announced as "Psalm 23", not "Chapter 23" — matching the
     * on-screen chapter header. Shared by the spoken text and the cache key so
     * the two can never drift apart.
     */
    static boolean isPsalmBook(String book) {
        return "Psalm".equals(book) || "Psalms".equals(book);
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
        return callTts(text, BearerPolicy.ALLOW_API_KEY);
    }

    private byte[] callTts(String text, BearerPolicy policy)
            throws IOException, InterruptedException {
        if (!isKnownProvider()) {
            log.warn("Unknown tts.provider '{}' — expected openai or xai; skipping TTS generation",
                    provider);
            return null;
        }
        if (policy == BearerPolicy.OAUTH_ONLY && !isXai()) {
            throw new AuthUnavailableException(
                    "Bulk generation requires provider=xai with SuperGrok OAuth; refusing to bill "
                            + providerKeySegment() + " per token");
        }
        String bearer = resolvedBearer(policy);
        if (bearer == null || bearer.isBlank()) {
            if (policy == BearerPolicy.OAUTH_ONLY) {
                throw new AuthUnavailableException(
                        "No SuperGrok OAuth access token — refusing to fall back to XAI_API_KEY "
                                + "for bulk generation");
            }
            log.warn("TTS unavailable: no OAuth access token and no API key configured");
            return null;
        }
        generationPermits.acquire();
        try {
            String requestBody = buildTtsRequestBody(text);

            log.debug("TTS provider={} url={} voice={}", provider, resolvedUrl(), resolvedVoice());

            HttpResponse<byte[]> response = sendTts(requestBody, bearer);

            // A rotated-out access token reads as 401. Mint a fresh one and retry
            // once before falling back to the static key — a whole prefetch batch
            // would otherwise die on one expiry. Under OAUTH_ONLY there is no such
            // fallback: retryXaiBearer would hand back XAI_API_KEY, so it is passed
            // a null key and a refusal is raised if no fresh token appears.
            if (isXai() && xaiOAuthTokenManager != null && response.statusCode() == 401
                    && XaiOAuthTokenManager.wasOAuthBearer(bearer, resolvedKey())) {
                xaiOAuthTokenManager.invalidate();
                String fallbackKey = policy == BearerPolicy.OAUTH_ONLY ? null : resolvedKey();
                String retryBearer = XaiOAuthTokenManager.retryXaiBearer(
                        xaiOAuthTokenManager, bearer, fallbackKey);
                if (retryBearer != null) {
                    log.warn("event=xai_oauth_rejected retrying_tts");
                    response = sendTts(requestBody, retryBearer);
                } else if (policy == BearerPolicy.OAUTH_ONLY) {
                    throw new AuthUnavailableException(
                            "SuperGrok OAuth token rejected (401) and could not be refreshed — "
                                    + "refusing to fall back to XAI_API_KEY for bulk generation");
                }
            }

            // 429/403 on an OAuth bearer is the subscription bucket running dry, not a
            // bad clip. Continuing would grind through the rest of the corpus failing,
            // so a bulk run stops here rather than being quietly downgraded.
            if (policy == BearerPolicy.OAUTH_ONLY
                    && (response.statusCode() == 429 || response.statusCode() == 403)) {
                throw new AuthUnavailableException(
                        "SuperGrok quota exhausted or forbidden (HTTP " + response.statusCode()
                                + ") — stopping bulk generation rather than billing XAI_API_KEY");
            }

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

    private HttpResponse<byte[]> sendTts(String requestBody, String bearer)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(resolvedUrl()))
                .header("Authorization", "Bearer " + bearer)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(60))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
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
