package com.readthekjv.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.readthekjv.model.Verse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TtsServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BibleService bibleService;
    private HttpClient httpClient;
    private S3Client s3Client;
    private TtsService service;

    @BeforeEach
    void setUp() {
        bibleService = mock(BibleService.class);
        httpClient = mock(HttpClient.class);
        s3Client = mock(S3Client.class);
        service = new TtsService(bibleService);
        ReflectionTestUtils.setField(service, "httpClient", httpClient);
        ReflectionTestUtils.setField(service, "s3Client", s3Client);
        ReflectionTestUtils.setField(service, "spacesCdnUrl", "https://cdn.example");
        ReflectionTestUtils.setField(service, "spacesBucket", "readthekjv");
        ReflectionTestUtils.setField(service, "audioPrefix", "audio");
        configure("openai", "sk-openai", "xai-key", "", "tts-1-hd");
    }

    private void configure(String provider, String openAiKey, String xaiKey, String voice, String model) {
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "provider", provider);
        ReflectionTestUtils.setField(service, "apiKey", openAiKey);
        ReflectionTestUtils.setField(service, "xaiKey", xaiKey);
        ReflectionTestUtils.setField(service, "voice", voice);
        ReflectionTestUtils.setField(service, "model", model);
    }

    // ── Provider URL / key / voice defaults ───────────────────────────────────

    @Test
    void openaiSelectsOpenAiUrlKeyAndDefaultVoice() throws Exception {
        configure("openai", "sk-openai", "xai-key", "", "tts-1-hd");

        assertTrue(service.isKnownProvider());
        assertFalse(service.isXai());
        assertEquals("https://api.openai.com/v1/audio/speech", service.resolvedUrl());
        assertEquals("sk-openai", service.resolvedKey());
        assertEquals("onyx", service.resolvedVoice());
        assertEquals("tts-1-hd", service.resolvedModel());
        assertTrue(service.isEnabled());

        JsonNode body = MAPPER.readTree(service.buildTtsRequestBody("In the beginning"));
        assertEquals("tts-1-hd", body.path("model").asText());
        assertEquals("In the beginning", body.path("input").asText());
        assertEquals("onyx", body.path("voice").asText());
        assertEquals("mp3", body.path("response_format").asText());
        assertTrue(body.path("text").isMissingNode());
        assertTrue(body.path("voice_id").isMissingNode());
    }

    @Test
    void xaiSelectsXaiUrlKeyAndDefaultVoice() throws Exception {
        configure("xai", "sk-openai", "xai-key", "", "tts-1-hd");

        assertTrue(service.isKnownProvider());
        assertTrue(service.isXai());
        assertEquals("https://api.x.ai/v1/tts", service.resolvedUrl());
        assertEquals("xai-key", service.resolvedKey());
        assertEquals("eve", service.resolvedVoice());
        assertTrue(service.isEnabled());

        JsonNode body = MAPPER.readTree(service.buildTtsRequestBody("In the beginning"));
        assertEquals("In the beginning", body.path("text").asText());
        assertEquals("eve", body.path("voice_id").asText());
        assertEquals("en", body.path("language").asText());
        assertEquals("mp3", body.path("output_format").path("codec").asText());
        assertTrue(body.path("model").isMissingNode());
        assertTrue(body.path("input").isMissingNode());
        assertTrue(body.path("voice").isMissingNode());
        assertTrue(body.path("response_format").isMissingNode());
    }

    @Test
    void ttsVoiceOverridesBothProviderDefaults() throws Exception {
        configure("openai", "sk-openai", "xai-key", "alloy", "tts-1-hd");
        assertEquals("alloy", service.resolvedVoice());
        assertEquals("alloy", MAPPER.readTree(service.buildTtsRequestBody("hi")).path("voice").asText());

        configure("xai", "sk-openai", "xai-key", "rex", "tts-1-hd");
        assertEquals("rex", service.resolvedVoice());
        assertEquals("rex", MAPPER.readTree(service.buildTtsRequestBody("hi")).path("voice_id").asText());
    }

    @Test
    void xaiVoiceIdIsCaseInsensitiveAndNotAnAllowlist() throws Exception {
        configure("xai", "sk-openai", "xai-key", "ARA", "tts-1-hd");
        assertEquals("ara", service.resolvedVoice());
        assertEquals("ara", MAPPER.readTree(service.buildTtsRequestBody("hi")).path("voice_id").asText());
        assertEquals("audio/xai/ara/verses/0/1.mp3", service.getVerseKey(1));

        configure("xai", "sk-openai", "xai-key", "Eve", "tts-1-hd");
        assertEquals("eve", service.resolvedVoice());
        assertEquals("audio/xai/eve/verses/0/1.mp3", service.getVerseKey(1));
    }

    @Test
    void blankVoiceFallsBackToProviderDefault() {
        configure("openai", "sk-openai", "xai-key", "   ", "tts-1-hd");
        assertEquals("onyx", service.resolvedVoice());

        configure("xai", "sk-openai", "xai-key", "", "tts-1-hd");
        assertEquals("eve", service.resolvedVoice());
    }

    @Test
    void providerMatchIsCaseInsensitive() {
        configure("XAI", "sk-openai", "xai-key", "", "tts-1-hd");
        assertTrue(service.isXai());
        assertEquals("https://api.x.ai/v1/tts", service.resolvedUrl());
        assertEquals("xai-key", service.resolvedKey());
        assertEquals("eve", service.resolvedVoice());
    }

    @Test
    void unsetProviderDefaultsToOpenAi() {
        configure(null, "sk-openai", "xai-key", "", "tts-1-hd");
        assertTrue(service.isKnownProvider());
        assertFalse(service.isXai());
        assertEquals("https://api.openai.com/v1/audio/speech", service.resolvedUrl());
        assertEquals("sk-openai", service.resolvedKey());
        assertEquals("onyx", service.resolvedVoice());
        assertTrue(service.isEnabled());
    }

    @Test
    void getAudioUrlForVersePostsToOpenAiUrlWithOpenAiBearer() throws Exception {
        configure("openai", "sk-openai", "xai-key", "", "tts-1-hd");
        stubCacheMiss();
        stubHttp(200, new byte[] {1, 2, 3});
        when(bibleService.getVerse(1)).thenReturn(Optional.of(
                new Verse(1, "Genesis", 1, 1, 1, "In the beginning")));

        Optional<String> url = service.getAudioUrlForVerse(1);

        HttpRequest sent = capturedRequest();
        assertEquals("https://api.openai.com/v1/audio/speech", sent.uri().toString());
        assertEquals("Bearer sk-openai", sent.headers().firstValue("Authorization").orElseThrow());
        assertTrue(url.isPresent());
        assertEquals("https://cdn.example/audio/openai/onyx/verses/0/1.mp3", url.get());
        verify(s3Client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void getAudioUrlForVersePostsToXaiUrlWithXaiBearer() throws Exception {
        configure("xai", "sk-openai", "xai-secret", "", "tts-1-hd");
        stubCacheMiss();
        stubHttp(200, new byte[] {1, 2, 3});
        when(bibleService.getVerse(1)).thenReturn(Optional.of(
                new Verse(1, "Genesis", 1, 1, 1, "In the beginning")));

        Optional<String> url = service.getAudioUrlForVerse(1);

        HttpRequest sent = capturedRequest();
        assertEquals("https://api.x.ai/v1/tts", sent.uri().toString());
        assertEquals("Bearer xai-secret", sent.headers().firstValue("Authorization").orElseThrow());
        assertTrue(url.isPresent());
        assertEquals("https://cdn.example/audio/xai/eve/verses/0/1.mp3", url.get());
    }

    // ── Unknown provider fail-closed ──────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"anthropic", "opena", "openaii", "  ", ""})
    void unknownProviderSkipsGenerationAndDoesNotCallHttp(String provider) throws Exception {
        configure(provider, "sk-openai", "xai-key", "", "tts-1-hd");
        stubHttp(200, new byte[] {1, 2, 3});
        when(bibleService.getVerse(1)).thenReturn(Optional.of(
                new Verse(1, "Genesis", 1, 1, 1, "In the beginning")));

        assertFalse(service.isKnownProvider());
        assertFalse(service.isEnabled());
        assertTrue(service.getAudioUrlForVerse(1).isEmpty());
        verify(httpClient, never()).send(any(), any());
        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void missingOpenAiKeyDisablesService() {
        configure("openai", "", "xai-key", "", "tts-1-hd");
        assertFalse(service.isEnabled());
    }

    @Test
    void missingXaiKeyDisablesService() {
        configure("xai", "sk-openai", "  ", "", "tts-1-hd");
        assertFalse(service.isEnabled());
    }

    // ── Cache-key namespacing ─────────────────────────────────────────────────

    @Test
    void openaiOnyxVerseKeyIsNamespacedAndFallsBackToLegacy() {
        configure("openai", "sk-openai", "xai-key", "", "tts-1-hd");

        assertEquals("audio/openai/onyx/verses/0/1.mp3", service.getVerseKey(1));
        assertEquals("audio/verses/0/1.mp3", service.getLegacyVerseKey(1));
        assertEquals("audio/openai/onyx/verses/26/26137.mp3", service.getVerseKey(26137));
        assertEquals("audio/verses/26/26137.mp3", service.getLegacyVerseKey(26137));
        assertTrue(service.usesLegacyCache());
        assertEquals(
                List.of("audio/openai/onyx/verses/0/1.mp3", "audio/verses/0/1.mp3"),
                service.verseCacheKeys(1));
    }

    @Test
    void openaiOnyxChapterKeyIsNamespacedAndFallsBackToLegacy() {
        configure("openai", "sk-openai", "xai-key", "onyx", "tts-1-hd");

        assertEquals("audio/openai/onyx/chapters/1_John_3.mp3", service.getChapterKey("1 John", 3));
        assertEquals("audio/chapters/1_John_3.mp3", service.getLegacyChapterKey("1 John", 3));
        assertEquals(
                List.of("audio/openai/onyx/chapters/1_John_3.mp3", "audio/chapters/1_John_3.mp3"),
                service.chapterCacheKeys("1 John", 3));
    }

    @Test
    void xaiEveKeysAreNamespacedAndDoNotFallBackToLegacy() {
        configure("xai", "sk-openai", "xai-key", "", "tts-1-hd");

        assertEquals("audio/xai/eve/verses/0/1.mp3", service.getVerseKey(1));
        assertEquals("audio/xai/eve/chapters/Genesis_1.mp3", service.getChapterKey("Genesis", 1));
        assertFalse(service.usesLegacyCache());
        assertEquals(List.of("audio/xai/eve/verses/0/1.mp3"), service.verseCacheKeys(1));
        assertEquals(List.of("audio/xai/eve/chapters/Genesis_1.mp3"), service.chapterCacheKeys("Genesis", 1));
    }

    @Test
    void flippingVoiceChangesCacheKeyAndDropsLegacyFallback() {
        configure("openai", "sk-openai", "xai-key", "alloy", "tts-1-hd");

        assertEquals("audio/openai/alloy/verses/0/1.mp3", service.getVerseKey(1));
        assertFalse(service.usesLegacyCache());
        assertEquals(List.of("audio/openai/alloy/verses/0/1.mp3"), service.verseCacheKeys(1));
    }

    @Test
    void anyXaiVoiceIdIsAcceptedWithoutAnEnum() {
        configure("xai", "sk-openai", "xai-key", "nlbqfwie", "tts-1-hd");
        assertEquals("nlbqfwie", service.resolvedVoice());
        assertEquals("audio/xai/nlbqfwie/verses/0/1.mp3", service.getVerseKey(1));
        assertEquals(List.of("audio/xai/nlbqfwie/verses/0/1.mp3"), service.verseCacheKeys(1));
    }

    @Test
    void flippingXaiVoiceChangesCacheKey() {
        configure("xai", "sk-openai", "xai-key", "ara", "tts-1-hd");
        assertEquals("audio/xai/ara/verses/0/1.mp3", service.getVerseKey(1));
        assertEquals("audio/xai/ara/chapters/Genesis_1.mp3", service.getChapterKey("Genesis", 1));

        configure("xai", "sk-openai", "xai-key", "rex", "tts-1-hd");
        assertEquals("audio/xai/rex/verses/0/1.mp3", service.getVerseKey(1));
        assertFalse(service.verseCacheKeys(1).contains("audio/xai/ara/verses/0/1.mp3"));
        assertFalse(service.verseCacheKeys(1).contains("audio/xai/eve/verses/0/1.mp3"));
    }

    @Test
    void unknownXaiVoice404SkipsPersistAndDoesNotThrow() throws Exception {
        configure("xai", "sk-openai", "xai-key", "not-a-voice", "tts-1-hd");
        stubCacheMiss();
        stubHttp(404, "{\"error\":\"unknown voice_id\"}".getBytes());
        when(bibleService.getVerse(1)).thenReturn(Optional.of(
                new Verse(1, "Genesis", 1, 1, 1, "In the beginning")));

        Optional<String> url = assertDoesNotThrow(() -> service.getAudioUrlForVerse(1));

        assertTrue(url.isEmpty());
        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void findCachedAudioUrlForVerseHitsLegacyOpenAiOnyxKey() {
        configure("openai", "sk-openai", "xai-key", "", "tts-1-hd");
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenAnswer(inv -> {
            HeadObjectRequest req = inv.getArgument(0);
            if ("audio/verses/0/1.mp3".equals(req.key())) {
                return null;
            }
            throw NoSuchKeyException.builder().message("missing").build();
        });

        Optional<String> url = service.findCachedAudioUrlForVerse(1);

        assertEquals(Optional.of("https://cdn.example/audio/verses/0/1.mp3"), url);
    }

    @Test
    void findCachedAudioUrlForVerseIgnoresLegacyKeyWhenProviderIsXai() {
        configure("xai", "sk-openai", "xai-key", "", "tts-1-hd");
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenAnswer(inv -> {
            HeadObjectRequest req = inv.getArgument(0);
            if ("audio/verses/0/1.mp3".equals(req.key())) {
                return null;
            }
            throw NoSuchKeyException.builder().message("missing").build();
        });

        assertTrue(service.findCachedAudioUrlForVerse(1).isEmpty());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void stubCacheMiss() {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("missing").build());
    }

    @SuppressWarnings("unchecked")
    private void stubHttp(int status, byte[] body) throws Exception {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
    }

    private HttpRequest capturedRequest() throws Exception {
        ArgumentCaptor<HttpRequest> cap = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(cap.capture(), any());
        return cap.getValue();
    }
}
