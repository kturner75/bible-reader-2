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
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Service for text-to-speech audio generation using OpenAI's TTS API.
 * Stores audio files in Digital Ocean Spaces with CDN delivery.
 */
@Service
public class TtsService {

    private static final Logger log = LoggerFactory.getLogger(TtsService.class);

    @Value("${tts.enabled:false}")
    private boolean enabled;

    @Value("${tts.api-key:}")
    private String apiKey;

    @Value("${tts.voice:onyx}")
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
    private final HttpClient httpClient;
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
                log.info("TTS service initialized - voice: {}, model: {}, spaces: {}, cdn: {}",
                        voice, model, spacesBucket, spacesCdnUrl);
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
     * Returns true if TTS is enabled AND properly configured.
     */
    public boolean isEnabled() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }

    /**
     * Gets the CDN URL for a verse audio, generating it if not already in Spaces.
     * Also triggers background prefetch of upcoming verses.
     *
     * @param verseId The verse ID (1-31102)
     * @return Optional containing the CDN URL, or empty if unavailable
     */
    public Optional<String> getAudioUrlForVerse(int verseId) {
        if (!isEnabled() || s3Client == null) {
            return Optional.empty();
        }

        String key = getVerseKey(verseId);

        // Check if exists in Spaces
        if (existsInSpaces(key)) {
            // Trigger background prefetch of next verses
            triggerPrefetch(verseId);
            return Optional.of(getCdnUrl(key));
        }

        // Generate and upload
        Optional<Verse> verseOpt = bibleService.getVerse(verseId);
        if (verseOpt.isEmpty()) {
            log.warn("Verse not found: {}", verseId);
            return Optional.empty();
        }

        Verse verse = verseOpt.get();
        String speechText = formatVerseForSpeech(verse);

        try {
            byte[] audioData = callOpenAiTts(speechText);
            if (audioData == null || audioData.length == 0) {
                return Optional.empty();
            }

            uploadToSpaces(key, audioData);
            log.info("Generated and uploaded verse {}: {}", verseId, key);

            // Trigger background prefetch
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
     * @param book The book name
     * @param chapter The chapter number
     * @return Optional containing the CDN URL, or empty if unavailable
     */
    public Optional<String> getAudioUrlForChapter(String book, int chapter) {
        if (!isEnabled() || s3Client == null) {
            return Optional.empty();
        }

        String key = getChapterKey(book, chapter);

        // Check if exists in Spaces
        if (existsInSpaces(key)) {
            return Optional.of(getCdnUrl(key));
        }

        // Generate and upload
        String speechText = formatChapterForSpeech(book, chapter);

        try {
            byte[] audioData = callOpenAiTts(speechText);
            if (audioData == null || audioData.length == 0) {
                return Optional.empty();
            }

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
     * Triggers background prefetch of upcoming verses.
     */
    private void triggerPrefetch(int currentVerseId) {
        if (prefetchExecutor == null) return;

        prefetchExecutor.submit(() -> {
            int totalVerses = bibleService.getTotalVerses();
            for (int i = 1; i <= prefetchCount; i++) {
                int verseId = currentVerseId + i;
                if (verseId > totalVerses) break;

                String key = getVerseKey(verseId);
                if (!existsInSpaces(key)) {
                    try {
                        Optional<Verse> verseOpt = bibleService.getVerse(verseId);
                        if (verseOpt.isPresent()) {
                            String speechText = formatVerseForSpeech(verseOpt.get());
                            byte[] audioData = callOpenAiTts(speechText);
                            if (audioData != null && audioData.length > 0) {
                                uploadToSpaces(key, audioData);
                                log.debug("Prefetched verse {}", verseId);
                            }
                        }
                    } catch (Exception e) {
                        log.debug("Prefetch failed for verse {}: {}", verseId, e.getMessage());
                    }
                }
            }
        });
    }

    private String getVerseKey(int verseId) {
        int bucket = verseId / 1000;
        return audioPrefix + "/verses/" + bucket + "/" + verseId + ".mp3";
    }

    private String getChapterKey(String book, int chapter) {
        String safeBookName = book.replace(" ", "_");
        return audioPrefix + "/chapters/" + safeBookName + "_" + chapter + ".mp3";
    }

    private String getCdnUrl(String key) {
        return spacesCdnUrl + "/" + key;
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
     * Calls OpenAI's TTS API to generate audio.
     */
    private byte[] callOpenAiTts(String text) throws IOException, InterruptedException {
        String requestBody = String.format(
                "{\"model\": \"%s\", \"input\": %s, \"voice\": \"%s\", \"response_format\": \"mp3\"}",
                model, escapeJson(text), voice);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/audio/speech"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(60))
                .build();

        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() != 200) {
            log.error("OpenAI TTS API error: {} - {}", response.statusCode(), new String(response.body()));
            return null;
        }

        return response.body();
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
