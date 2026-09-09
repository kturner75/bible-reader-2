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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TtsServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BibleService bibleService;
    private HttpClient httpClient;
    private S3Client s3Client;
    private XaiOAuthTokenManager oauth;
    private TtsService service;

    @BeforeEach
    void setUp() {
        bibleService = mock(BibleService.class);
        httpClient = mock(HttpClient.class);
        s3Client = mock(S3Client.class);
        oauth = mock(XaiOAuthTokenManager.class);
        service = new TtsService(bibleService, oauth);
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
    void verseKeyIsNamespacedWithNoLegacyFallback() {
        configure("openai", "sk-openai", "xai-key", "", "tts-1-hd");

        // The unversioned audio/verses/… objects were deleted once xai/helios was
        // complete, so no key family falls back any more.
        assertEquals("audio/openai/onyx/verses/0/1.mp3", service.getVerseKey(1));
        assertEquals("audio/openai/onyx/verses/26/26137.mp3", service.getVerseKey(26137));
        assertEquals(List.of("audio/openai/onyx/verses/0/1.mp3"), service.verseCacheKeys(1));
    }

    @Test
    void chapterAnnouncementIsSharedByEveryBookWithThatChapterNumber() {
        configure("openai", "sk-openai", "xai-key", "onyx", "tts-1-hd");

        // "Chapter 3" names no book, so one object serves them all.
        assertEquals("audio/openai/onyx/chapters/3.mp3", service.getChapterKey("1 John", 3));
        assertEquals(service.getChapterKey("Genesis", 3), service.getChapterKey("1 John", 3));
        assertEquals(List.of("audio/openai/onyx/chapters/3.mp3"), service.chapterCacheKeys("1 John", 3));
    }

    @Test
    void psalmsGetTheirOwnChapterClipBecauseTheWordingDiffers() {
        configure("openai", "sk-openai", "xai-key", "onyx", "tts-1-hd");

        assertEquals("audio/openai/onyx/chapters/psalm_23.mp3", service.getChapterKey("Psalm", 23));
        assertEquals("... Psalm 23 ...", service.formatChapterForSpeech("Psalm", 23));
        assertEquals("... Chapter 23 ...", service.formatChapterForSpeech("Genesis", 23));
        assertFalse(service.getChapterKey("Psalm", 23).equals(service.getChapterKey("Genesis", 23)));
    }

    @Test
    void chapterKeysNeverFallBackToThePerBookLegacyLayout() {
        configure("openai", "sk-openai", "xai-key", "onyx", "tts-1-hd");

        // usesLegacyCache still governs verses; chapters opted out when the
        // per-book duplicates were collapsed.
        assertEquals(1, service.chapterCacheKeys("Genesis", 1).size());
        assertFalse(service.chapterCacheKeys("Genesis", 1).contains("audio/chapters/Genesis_1.mp3"));
    }

    @Test
    void bookAnnouncementIsKeyedByBookAndHasNoLegacyFallback() {
        configure("openai", "sk-openai", "xai-key", "onyx", "tts-1-hd");

        assertEquals("audio/openai/onyx/books/1_John.mp3", service.getBookKey("1 John"));
        assertEquals("audio/openai/onyx/books/Song_of_Solomon.mp3",
                service.getBookKey("Song of Solomon"));
        assertEquals(List.of("audio/openai/onyx/books/1_John.mp3"), service.bookCacheKeys("1 John"));
    }

    @Test
    void bookAnnouncementSpeaksTheAuthorizedVersionTitle() {
        assertEquals("... The Second Book of Moses, called Exodus ...",
                service.formatBookForSpeech("Exodus"));
        assertEquals("... The First Epistle General of John ...",
                service.formatBookForSpeech("1 John"));
        assertEquals("... The Book of Psalms ...", service.formatBookForSpeech("Psalm"));
        assertEquals("... Malachi ...", service.formatBookForSpeech("Malachi"));
        // Unknown book: degrade to the bare name rather than fail.
        assertEquals("... Nowhere ...", service.formatBookForSpeech("Nowhere"));
    }

    @Test
    void everyCanonicalBookHasASpokenTitle() {
        // 66 hand-written titles — a typo in a book name would silently degrade
        // that book's announcement to its bare name at runtime.
        assertEquals(BibleService.BOOK_ORDER.size(), TtsService.KJV_BOOK_TITLES.size());
        for (String book : BibleService.BOOK_ORDER) {
            assertTrue(TtsService.KJV_BOOK_TITLES.containsKey(book), "no title for " + book);
        }
    }

    @Test
    void xaiEveKeysAreNamespacedAndDoNotFallBackToLegacy() {
        configure("xai", "sk-openai", "xai-key", "", "tts-1-hd");

        assertEquals("audio/xai/eve/verses/0/1.mp3", service.getVerseKey(1));
        assertEquals("audio/xai/eve/chapters/1.mp3", service.getChapterKey("Genesis", 1));
        assertEquals("audio/xai/eve/books/Genesis.mp3", service.getBookKey("Genesis"));
        assertEquals(List.of("audio/xai/eve/verses/0/1.mp3"), service.verseCacheKeys(1));
        assertEquals(List.of("audio/xai/eve/chapters/1.mp3"), service.chapterCacheKeys("Genesis", 1));
    }

    @Test
    void flippingVoiceChangesCacheKeyAndDropsLegacyFallback() {
        configure("openai", "sk-openai", "xai-key", "alloy", "tts-1-hd");

        assertEquals("audio/openai/alloy/verses/0/1.mp3", service.getVerseKey(1));
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
        assertEquals("audio/xai/ara/chapters/1.mp3", service.getChapterKey("Genesis", 1));
        assertEquals("audio/xai/ara/books/Genesis.mp3", service.getBookKey("Genesis"));

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
    void openaiJsonEscapesVoiceAndModelQuotesAndBackslashes() throws Exception {
        configure("openai", "sk-openai", "xai-key", "onyx\"\\x", "tts-1-\"hd\\");
        String json = service.buildTtsRequestBody("say \"hi\"");
        JsonNode body = MAPPER.readTree(json);

        assertEquals("onyx\"\\x", body.path("voice").asText());
        assertEquals("tts-1-\"hd\\", body.path("model").asText());
        assertEquals("say \"hi\"", body.path("input").asText());
        assertTrue(json.contains("\\\""));
        assertTrue(json.contains("\\\\"));
    }

    @Test
    void voiceKeySegmentNeutralizesPathTricksButApiKeepsPassThrough() throws Exception {
        configure("xai", "sk-openai", "xai-key", "../etc/passwd", "tts-1-hd");
        assertEquals("../etc/passwd", service.resolvedVoice());
        assertEquals("../etc/passwd",
                MAPPER.readTree(service.buildTtsRequestBody("hi")).path("voice_id").asText());
        assertEquals("___etc_passwd", service.voiceKeySegment());
        assertEquals("audio/xai/___etc_passwd/verses/0/1.mp3", service.getVerseKey(1));
        assertFalse(service.getVerseKey(1).contains(".."));
        assertTrue(service.getVerseKey(1).startsWith("audio/xai/"));
    }

    @Test
    void voiceKeySegmentNeutralizesSlashDotDotAndBackslash() {
        configure("openai", "sk-openai", "xai-key", "foo/../../bar\\baz", "tts-1-hd");
        assertEquals("foo/../../bar\\baz", service.resolvedVoice());
        assertEquals("foo_______bar_baz", service.voiceKeySegment());
        assertEquals("audio/openai/foo_______bar_baz/verses/0/1.mp3", service.getVerseKey(1));
        assertEquals("audio/openai/foo_______bar_baz/chapters/1.mp3",
                service.getChapterKey("Genesis", 1));
        assertFalse(service.getVerseKey(1).contains(".."));
        assertFalse(service.getVerseKey(1).contains("\\"));
    }

    @Test
    void findCachedAudioUrlForVerseNoLongerConsultsTheLegacyKey() {
        configure("openai", "sk-openai", "xai-key", "", "tts-1-hd");
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenAnswer(inv -> {
            HeadObjectRequest req = inv.getArgument(0);
            if ("audio/verses/0/1.mp3".equals(req.key())) {
                return null;
            }
            throw NoSuchKeyException.builder().message("missing").build();
        });

        // The unversioned object exists, and is deliberately ignored: those 3,329
        // openai/onyx files were deleted once xai/helios was complete, so reading
        // them would only resurrect a layout that no longer exists.
        assertEquals(Optional.empty(), service.findCachedAudioUrlForVerse(1));
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

    // ── SuperGrok OAuth bearer ────────────────────────────────────────────────

    @Test
    void xaiPrefersOAuthAccessTokenOverTheStaticApiKey() {
        configure("xai", "sk-openai", "xai-key", "", "tts-1-hd");
        when(oauth.getAccessToken()).thenReturn(Optional.of("oauth-access-token"));

        // Subscription quota, not pay-per-token.
        assertEquals("oauth-access-token", service.resolvedBearer());
    }

    @Test
    void xaiFallsBackToApiKeyWhenNoOAuthTokenIsAvailable() {
        configure("xai", "sk-openai", "xai-key", "", "tts-1-hd");
        when(oauth.getAccessToken()).thenReturn(Optional.empty());

        assertEquals("xai-key", service.resolvedBearer());
    }

    @Test
    void openaiNeverConsultsTheXaiOAuthManager() {
        configure("openai", "sk-openai", "xai-key", "", "tts-1-hd");

        assertEquals("sk-openai", service.resolvedBearer());
        verify(oauth, never()).getAccessToken();
    }

    @Test
    void xaiIsEnabledOnOAuthAloneWithNoApiKey() {
        configure("xai", "sk-openai", "", "", "tts-1-hd");
        when(oauth.isConfigured()).thenReturn(true);

        assertTrue(service.isEnabled());
        // Availability must not burn a refresh — isConfigured() is the local check.
        verify(oauth, never()).getAccessToken();
    }

    @Test
    void xaiIsDisabledWithNeitherOAuthNorApiKey() {
        configure("xai", "sk-openai", "", "", "tts-1-hd");
        when(oauth.isConfigured()).thenReturn(false);

        assertFalse(service.isEnabled());
    }

    @Test
    void oauthRejectionRetriesOnceWithAFreshToken() throws Exception {
        configure("xai", "sk-openai", "xai-key", "", "tts-1-hd");
        stubCacheMiss();
        when(oauth.getAccessToken())
                .thenReturn(Optional.of("stale-token"))
                .thenReturn(Optional.of("fresh-token"));
        when(bibleService.getVerse(1)).thenReturn(Optional.of(
                new Verse(1, "Genesis", 1, 1, 1, "In the beginning")));

        HttpResponse<byte[]> rejected = mock(HttpResponse.class);
        when(rejected.statusCode()).thenReturn(401);
        HttpResponse<byte[]> ok = mock(HttpResponse.class);
        when(ok.statusCode()).thenReturn(200);
        when(ok.body()).thenReturn(new byte[] { 1, 2, 3 });
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(rejected, ok);

        assertTrue(service.getAudioUrlForVerse(1).isPresent());

        verify(oauth).invalidate();
        ArgumentCaptor<HttpRequest> requests = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient, times(2)).send(requests.capture(), any(HttpResponse.BodyHandler.class));
        assertEquals("Bearer stale-token",
                requests.getAllValues().get(0).headers().firstValue("Authorization").orElseThrow());
        assertEquals("Bearer fresh-token",
                requests.getAllValues().get(1).headers().firstValue("Authorization").orElseThrow());
    }

    // ── Bulk generation must never bill the metered key ───────────────────────

    @Test
    void oauthOnlyReturnsNoBearerRatherThanTheApiKey() {
        configure("xai", "sk-openai", "xai-key", "", "tts-1-hd");
        when(oauth.getAccessToken()).thenReturn(Optional.empty());

        // Serving still falls back; bulk must not.
        assertEquals("xai-key", service.resolvedBearer(TtsService.BearerPolicy.ALLOW_API_KEY));
        assertNull(service.resolvedBearer(TtsService.BearerPolicy.OAUTH_ONLY));
        assertFalse(service.hasOAuthBearer());
    }

    @Test
    void bulkGenerationRefusesToStartWithoutAnOAuthToken() throws Exception {
        configure("xai", "sk-openai", "xai-key", "", "tts-1-hd");
        when(oauth.getAccessToken()).thenReturn(Optional.empty());

        TtsService.AuthUnavailableException e = assertThrows(
                TtsService.AuthUnavailableException.class,
                () -> service.generateAndUpload("audio/xai/eve/books/Genesis.mp3", "... Genesis ..."));
        assertTrue(e.getMessage().contains("XAI_API_KEY"));
        // The decisive assertion: no HTTP call, so nothing was billed.
        verify(httpClient, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void bulkGenerationRefusesANonXaiProviderOutright() {
        configure("openai", "sk-openai", "xai-key", "", "tts-1-hd");

        assertThrows(TtsService.AuthUnavailableException.class,
                () -> service.generateAndUpload("audio/openai/onyx/books/Genesis.mp3", "... Genesis ..."));
    }

    @Test
    void bulkGenerationAbortsRatherThanRetryingOn401WithTheApiKey() throws Exception {
        configure("xai", "sk-openai", "xai-key", "", "tts-1-hd");
        // A token is minted, then rejected, and no fresh one is available. The serving
        // path would retry on xai-key here; the bulk path must refuse.
        when(oauth.getAccessToken())
                .thenReturn(Optional.of("stale-token"))
                .thenReturn(Optional.empty());
        stubHttp(401, new byte[0]);

        TtsService.AuthUnavailableException e = assertThrows(
                TtsService.AuthUnavailableException.class,
                () -> service.generateAndUpload("audio/xai/eve/books/Genesis.mp3", "... Genesis ..."));
        assertTrue(e.getMessage().contains("XAI_API_KEY"));

        // Exactly one call — the rejected one. No retry reached for the metered key.
        verify(httpClient, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void exhaustedSubscriptionStopsTheRunInsteadOfDowngrading() throws Exception {
        configure("xai", "sk-openai", "xai-key", "", "tts-1-hd");
        when(oauth.getAccessToken()).thenReturn(Optional.of("oauth-access-token"));
        stubHttp(429, "quota exhausted".getBytes());

        TtsService.AuthUnavailableException e = assertThrows(
                TtsService.AuthUnavailableException.class,
                () -> service.generateAndUpload("audio/xai/eve/books/Genesis.mp3", "... Genesis ..."));
        assertTrue(e.getMessage().contains("quota exhausted") || e.getMessage().contains("429"));
        verify(httpClient, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void servingPathKeepsItsApiKeyFallbackOn401() throws Exception {
        configure("xai", "sk-openai", "xai-key", "", "tts-1-hd");
        stubCacheMiss();
        when(oauth.getAccessToken())
                .thenReturn(Optional.of("stale-token"))
                .thenReturn(Optional.empty());
        when(bibleService.getVerse(1)).thenReturn(Optional.of(
                new Verse(1, "Genesis", 1, 1, 1, "In the beginning")));

        HttpResponse<byte[]> rejected = mock(HttpResponse.class);
        when(rejected.statusCode()).thenReturn(401);
        HttpResponse<byte[]> ok = mock(HttpResponse.class);
        when(ok.statusCode()).thenReturn(200);
        when(ok.body()).thenReturn(new byte[] { 1, 2, 3 });
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(rejected, ok);

        // One reader waiting on one clip is worth the metered fallback.
        assertTrue(service.getAudioUrlForVerse(1).isPresent());
        ArgumentCaptor<HttpRequest> requests = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient, times(2)).send(requests.capture(), any(HttpResponse.BodyHandler.class));
        assertEquals("Bearer xai-key",
                requests.getAllValues().get(1).headers().firstValue("Authorization").orElseThrow());
    }

    // ── Voice as a parameter, not just server config ──────────────────────────

    @Test
    void keysCanBeBuiltForAVoiceOtherThanTheConfiguredOne() {
        configure("xai", "sk-openai", "xai-key", "helios", "tts-1-hd");

        // Same server, same request, different corpus — this is what makes a
        // per-reader voice choice possible at all.
        assertEquals("audio/xai/helios/verses/0/1.mp3", service.getVerseKey(1));
        assertEquals("audio/xai/ara/verses/0/1.mp3", service.getVerseKey(1, "ara"));
        assertEquals("audio/xai/ara/books/1_John.mp3", service.getBookKey("1 John", "ara"));
        assertEquals("audio/xai/ara/chapters/3.mp3", service.getChapterKey("Genesis", 3, "ara"));
        assertEquals("audio/xai/ara/chapters/psalm_23.mp3", service.getChapterKey("Psalm", 23, "ara"));
    }

    @Test
    void anExplicitVoiceIsStillSanitizedIntoOnePathSegment() {
        configure("xai", "sk-openai", "xai-key", "helios", "tts-1-hd");

        // A voice reaching the key builder from a request must not escape the prefix.
        // Dots are not in the allowed set either, so traversal collapses to underscores.
        assertEquals("audio/xai/______etc/verses/0/1.mp3", service.getVerseKey(1, "../../etc"));
        assertEquals("audio/xai/_/verses/0/1.mp3", service.getVerseKey(1, ""));
        assertEquals("audio/xai/_/verses/0/1.mp3", service.getVerseKey(1, null));
    }

    @Test
    void theXaiRosterBearerIsNeverAnOpenAiKey() {
        configure("openai", "sk-openai", "xai-key", "", "tts-1-hd");

        // This bearer is sent to api.x.ai. Under the openai provider resolvedBearer()
        // is OPENAI_API_KEY, so returning it would disclose one provider's credential
        // to another.
        assertNull(service.metadataBearer());

        configure("xai", "sk-openai", "xai-key", "", "tts-1-hd");
        when(oauth.getAccessToken()).thenReturn(Optional.empty());
        assertEquals("xai-key", service.metadataBearer());
    }

    @Test
    void spacesReadinessIsReportedSoBulkRunsCanRefuseToBurnQuota() {
        configure("xai", "sk-openai", "xai-key", "", "tts-1-hd");
        assertTrue(service.isSpacesReady());

        ReflectionTestUtils.setField(service, "s3Client", null);
        // callTts would still spend; uploadToSpaces would then NPE. A bulk run must
        // be able to see this coming rather than discover it 31k clips in.
        assertFalse(service.isSpacesReady());
    }
}
