package com.readthekjv.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.readthekjv.model.entity.VerseOfDay;
import com.readthekjv.repository.VerseOfDayRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VerseOfDayServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 8, 29);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private VerseOfDayRepository repository;
    private BibleService bibleService;
    private HttpClient httpClient;
    private VerseOfDayService service;

    @BeforeEach
    void setUp() {
        repository   = mock(VerseOfDayRepository.class);
        bibleService = mock(BibleService.class);
        httpClient   = mock(HttpClient.class);
        service      = new VerseOfDayService(repository, bibleService, unconfiguredOAuth());
        ReflectionTestUtils.setField(service, "httpClient", httpClient);
        configure("openai", "sk-openai", "xai-key", "");
        when(repository.existsById(DATE)).thenReturn(false);
        when(repository.findTop365ByOrderByDateDesc()).thenReturn(List.of());
    }

    private static XaiOAuthTokenManager unconfiguredOAuth() {
        return new XaiOAuthTokenManager("", true, null);
    }

    private void configure(String provider, String openAiKey, String xaiKey, String model) {
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "provider", provider);
        ReflectionTestUtils.setField(service, "openAiKey", openAiKey);
        ReflectionTestUtils.setField(service, "xaiKey", xaiKey);
        ReflectionTestUtils.setField(service, "model", model);
    }

    // ── Provider URL / key / model ────────────────────────────────────────────

    @Test
    void openaiSelectsOpenAiUrlKeyAndDefaultModel() throws Exception {
        configure("openai", "sk-openai", "xai-key", "");

        assertEquals("https://api.openai.com/v1/chat/completions", service.resolvedUrl());
        assertEquals("sk-openai", service.resolvedKey());
        assertEquals("gpt-4o-mini", service.resolvedModel());

        JsonNode body = MAPPER.readTree(service.buildChatRequestBody("prompt"));
        assertEquals("gpt-4o-mini", body.path("model").asText());
        assertEquals(0.7, body.path("temperature").asDouble());
        assertTrue(body.path("messages").isArray());
        assertEquals(2, body.path("messages").size());
    }

    @Test
    void xaiSelectsXaiUrlKeyAndDefaultModel() throws Exception {
        configure("xai", "sk-openai", "xai-key", "");

        assertEquals("https://api.x.ai/v1/chat/completions", service.resolvedUrl());
        assertEquals("xai-key", service.resolvedKey());
        assertEquals("grok-3-mini", service.resolvedModel());

        JsonNode body = MAPPER.readTree(service.buildChatRequestBody("prompt"));
        assertEquals("grok-3-mini", body.path("model").asText());
        assertEquals(0.7, body.path("temperature").asDouble());
        assertTrue(body.path("messages").isArray());
    }

    @Test
    void providerMatchIsCaseInsensitive() {
        configure("XAI", "sk-openai", "xai-key", "");
        assertTrue(service.isXai());
        assertEquals("https://api.x.ai/v1/chat/completions", service.resolvedUrl());
        assertEquals("xai-key", service.resolvedKey());
        assertEquals("grok-3-mini", service.resolvedModel());
    }

    @Test
    void unsetProviderDefaultsToOpenAi() {
        configure(null, "sk-openai", "xai-key", "");
        assertTrue(service.isKnownProvider());
        assertEquals("https://api.openai.com/v1/chat/completions", service.resolvedUrl());
        assertEquals("sk-openai", service.resolvedKey());
        assertEquals("gpt-4o-mini", service.resolvedModel());
    }

    @Test
    void votdModelOverridesProviderDefault() throws Exception {
        configure("xai", "sk-openai", "xai-key", "grok-3");
        assertEquals("grok-3", service.resolvedModel());
        assertEquals("grok-3", MAPPER.readTree(service.buildChatRequestBody("p")).path("model").asText());

        configure("openai", "sk-openai", "xai-key", "gpt-4o");
        assertEquals("gpt-4o", service.resolvedModel());
        assertEquals("gpt-4o", MAPPER.readTree(service.buildChatRequestBody("p")).path("model").asText());
    }

    @Test
    void generateForDatePostsToOpenAiUrlWithOpenAiBearer() throws Exception {
        configure("openai", "sk-openai", "xai-key", "");
        stubHttp(200, chatEnvelope("{\"reference\":\"John 3:16\",\"blurb\":\"Hope.\"}"));
        when(bibleService.parseAndResolve("John 3:16")).thenReturn(Optional.of(26137));

        service.generateForDate(DATE);

        HttpRequest sent = capturedRequest();
        assertEquals("https://api.openai.com/v1/chat/completions", sent.uri().toString());
        assertEquals("Bearer sk-openai", sent.headers().firstValue("Authorization").orElseThrow());
    }

    @Test
    void generateForDatePostsToXaiUrlWithXaiBearer() throws Exception {
        configure("xai", "sk-openai", "xai-secret", "");
        stubHttp(200, chatEnvelope("{\"reference\":\"John 3:16\",\"blurb\":\"Hope.\"}"));
        when(bibleService.parseAndResolve("John 3:16")).thenReturn(Optional.of(26137));

        service.generateForDate(DATE);

        HttpRequest sent = capturedRequest();
        assertEquals("https://api.x.ai/v1/chat/completions", sent.uri().toString());
        assertEquals("Bearer xai-secret", sent.headers().firstValue("Authorization").orElseThrow());
    }

    @Test
    void generateForDatePostsToXaiUrlWithOAuthBearerWhenPresent() throws Exception {
        XaiOAuthTokenManager oauth = mock(XaiOAuthTokenManager.class);
        when(oauth.getAccessToken()).thenReturn(Optional.of("oauth-access-token"));
        service = new VerseOfDayService(repository, bibleService, oauth);
        ReflectionTestUtils.setField(service, "httpClient", httpClient);
        configure("xai", "sk-openai", "xai-secret", "");
        stubHttp(200, chatEnvelope("{\"reference\":\"John 3:16\",\"blurb\":\"Hope.\"}"));
        when(bibleService.parseAndResolve("John 3:16")).thenReturn(Optional.of(26137));

        service.generateForDate(DATE);

        HttpRequest sent = capturedRequest();
        assertEquals("https://api.x.ai/v1/chat/completions", sent.uri().toString());
        assertEquals("Bearer oauth-access-token", sent.headers().firstValue("Authorization").orElseThrow());
    }

    @Test
    void generateForDateFallsBackToXaiKeyWhenOAuthEmpty() throws Exception {
        XaiOAuthTokenManager oauth = mock(XaiOAuthTokenManager.class);
        when(oauth.getAccessToken()).thenReturn(Optional.empty());
        service = new VerseOfDayService(repository, bibleService, oauth);
        ReflectionTestUtils.setField(service, "httpClient", httpClient);
        configure("xai", "sk-openai", "xai-secret", "");
        stubHttp(200, chatEnvelope("{\"reference\":\"John 3:16\",\"blurb\":\"Hope.\"}"));
        when(bibleService.parseAndResolve("John 3:16")).thenReturn(Optional.of(26137));

        service.generateForDate(DATE);

        assertEquals("Bearer xai-secret", capturedRequest().headers().firstValue("Authorization").orElseThrow());
    }

    @Test
    void openaiProviderIgnoresXaiOAuthToken() throws Exception {
        XaiOAuthTokenManager oauth = mock(XaiOAuthTokenManager.class);
        when(oauth.getAccessToken()).thenReturn(Optional.of("oauth-access-token"));
        service = new VerseOfDayService(repository, bibleService, oauth);
        ReflectionTestUtils.setField(service, "httpClient", httpClient);
        configure("openai", "sk-openai", "xai-secret", "");
        stubHttp(200, chatEnvelope("{\"reference\":\"John 3:16\",\"blurb\":\"Hope.\"}"));
        when(bibleService.parseAndResolve("John 3:16")).thenReturn(Optional.of(26137));

        service.generateForDate(DATE);

        assertEquals("Bearer sk-openai", capturedRequest().headers().firstValue("Authorization").orElseThrow());
    }

    @Test
    void xaiOAuthOnlyWithNoApiKeyStillCallsXai() throws Exception {
        XaiOAuthTokenManager oauth = mock(XaiOAuthTokenManager.class);
        when(oauth.getAccessToken()).thenReturn(Optional.of("oauth-access-token"));
        when(oauth.isConfigured()).thenReturn(true);
        service = new VerseOfDayService(repository, bibleService, oauth);
        ReflectionTestUtils.setField(service, "httpClient", httpClient);
        configure("xai", "sk-openai", "", "");
        stubHttp(200, chatEnvelope("{\"reference\":\"John 3:16\",\"blurb\":\"Hope.\"}"));
        when(bibleService.parseAndResolve("John 3:16")).thenReturn(Optional.of(26137));

        service.generateForDate(DATE);

        assertEquals("Bearer oauth-access-token", capturedRequest().headers().firstValue("Authorization").orElseThrow());
    }

    @Test
    void existingVerseSkipsOAuthRefreshAndHttp() throws Exception {
        XaiOAuthTokenManager oauth = mock(XaiOAuthTokenManager.class);
        when(oauth.isConfigured()).thenReturn(true);
        when(oauth.getAccessToken()).thenReturn(Optional.of("oauth-access-token"));
        service = new VerseOfDayService(repository, bibleService, oauth);
        ReflectionTestUtils.setField(service, "httpClient", httpClient);
        configure("xai", "sk-openai", "xai-secret", "");
        when(repository.existsById(DATE)).thenReturn(true);

        service.generateForDate(DATE);

        verify(oauth, never()).getAccessToken();
        verify(httpClient, never()).send(any(), any());
        verify(repository, never()).save(any());
    }

    @Test
    void generateForDate_xai401_invalidatesAndRetriesWithRefreshedToken() throws Exception {
        XaiOAuthTokenManager oauth = mock(XaiOAuthTokenManager.class);
        when(oauth.getAccessToken())
                .thenReturn(Optional.of("dead-oauth"))
                .thenReturn(Optional.of("fresh-oauth"));
        service = new VerseOfDayService(repository, bibleService, oauth);
        ReflectionTestUtils.setField(service, "httpClient", httpClient);
        configure("xai", "sk-openai", "xai-secret", "");
        stubHttpSequence(401, "{\"error\":\"invalid token\"}",
                200, chatEnvelope("{\"reference\":\"John 3:16\",\"blurb\":\"Hope.\"}"));
        when(bibleService.parseAndResolve("John 3:16")).thenReturn(Optional.of(26137));

        service.generateForDate(DATE);

        verify(oauth).invalidate();
        var sent = capturedRequests(2);
        assertEquals("Bearer dead-oauth", sent.get(0).headers().firstValue("Authorization").orElseThrow());
        assertEquals("Bearer fresh-oauth", sent.get(1).headers().firstValue("Authorization").orElseThrow());
        verify(repository).save(any());
    }

    @Test
    void generateForDate_xai401_invalidatesAndRetriesWithApiKeyWhenRefreshEmpty() throws Exception {
        XaiOAuthTokenManager oauth = mock(XaiOAuthTokenManager.class);
        when(oauth.getAccessToken())
                .thenReturn(Optional.of("dead-oauth"))
                .thenReturn(Optional.empty());
        service = new VerseOfDayService(repository, bibleService, oauth);
        ReflectionTestUtils.setField(service, "httpClient", httpClient);
        configure("xai", "sk-openai", "xai-secret", "");
        stubHttpSequence(401, "{\"error\":\"invalid token\"}",
                200, chatEnvelope("{\"reference\":\"John 3:16\",\"blurb\":\"Hope.\"}"));
        when(bibleService.parseAndResolve("John 3:16")).thenReturn(Optional.of(26137));

        service.generateForDate(DATE);

        verify(oauth).invalidate();
        var sent = capturedRequests(2);
        assertEquals("Bearer dead-oauth", sent.get(0).headers().firstValue("Authorization").orElseThrow());
        assertEquals("Bearer xai-secret", sent.get(1).headers().firstValue("Authorization").orElseThrow());
        verify(repository).save(any());
    }

    // ── Junk / error paths skip persist ───────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"anthropic", "opena", "openaii", "  ", ""})
    void unknownProviderSkipsPersistAndDoesNotCallOpenAi(String provider) throws Exception {
        configure(provider, "sk-openai", "xai-key", "");
        stubHttp(200, chatEnvelope("{\"reference\":\"John 3:16\",\"blurb\":\"Hope.\"}"));
        when(bibleService.parseAndResolve("John 3:16")).thenReturn(Optional.of(26137));

        assertDoesNotThrow(() -> service.generateForDate(DATE));
        verify(httpClient, never()).send(any(), any());
        verify(repository, never()).save(any());
    }

    @Test
    void missingOpenAiKeySkipsPersistAndDoesNotCallHttp() throws Exception {
        configure("openai", "", "xai-key", "");
        assertDoesNotThrow(() -> service.generateForDate(DATE));
        verify(httpClient, never()).send(any(), any());
        verify(repository, never()).save(any());
    }

    @Test
    void missingXaiKeySkipsPersistAndDoesNotCallHttp() throws Exception {
        configure("xai", "sk-openai", "  ", "");
        assertDoesNotThrow(() -> service.generateForDate(DATE));
        verify(httpClient, never()).send(any(), any());
        verify(repository, never()).save(any());
    }

    @Test
    void http429CreditExhaustedSkipsPersist() throws Exception {
        stubHttp(429, "{\"error\":{\"code\":\"credit_balance_exhausted\"}}");
        assertDoesNotThrow(() -> service.generateForDate(DATE));
        verify(repository, never()).save(any());
    }

    @Test
    void http500SkipsPersist() throws Exception {
        stubHttp(500, "internal error");
        assertDoesNotThrow(() -> service.generateForDate(DATE));
        verify(repository, never()).save(any());
    }

    @Test
    void malformedJsonSkipsPersist() throws Exception {
        stubHttp(200, "not-json");
        assertDoesNotThrow(() -> service.generateForDate(DATE));
        verify(repository, never()).save(any());
    }

    @Test
    void missingReferenceSkipsPersist() throws Exception {
        stubHttp(200, chatEnvelope("{\"blurb\":\"no verse\"}"));
        assertDoesNotThrow(() -> service.generateForDate(DATE));
        verify(repository, never()).save(any());
    }

    @Test
    void junkUnresolvableReferenceSkipsPersist() throws Exception {
        stubHttp(200, chatEnvelope("{\"reference\":\"NotABook 99:1\",\"blurb\":\"nope\"}"));
        when(bibleService.parseAndResolve("NotABook 99:1")).thenReturn(Optional.empty());
        assertDoesNotThrow(() -> service.generateForDate(DATE));
        verify(repository, never()).save(any());
    }

    @Test
    void httpExceptionSkipsPersistAndDoesNotThrow() throws Exception {
        when(httpClient.send(any(HttpRequest.class), any())).thenThrow(new IOException("boom"));
        assertDoesNotThrow(() -> service.generateForDate(DATE));
        verify(repository, never()).save(any());
    }

    @Test
    void validResponsePersists() throws Exception {
        stubHttp(200, chatEnvelope("{\"reference\":\"John 3:16\",\"blurb\":\"For God so loved.\"}"));
        when(bibleService.parseAndResolve("John 3:16")).thenReturn(Optional.of(26137));

        service.generateForDate(DATE);

        ArgumentCaptor<VerseOfDay> cap = ArgumentCaptor.forClass(VerseOfDay.class);
        verify(repository).save(cap.capture());
        assertEquals(DATE, cap.getValue().getDate());
        assertEquals(26137, cap.getValue().getVerseId());
        assertEquals("For God so loved.", cap.getValue().getAiBlurb());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void stubHttp(int status, String body) throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
    }

    @SuppressWarnings("unchecked")
    private void stubHttpSequence(int status1, String body1, int status2, String body2) throws Exception {
        HttpResponse<String> first = mock(HttpResponse.class);
        when(first.statusCode()).thenReturn(status1);
        when(first.body()).thenReturn(body1);
        HttpResponse<String> second = mock(HttpResponse.class);
        when(second.statusCode()).thenReturn(status2);
        when(second.body()).thenReturn(body2);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(first, second);
    }

    private HttpRequest capturedRequest() throws Exception {
        return capturedRequests(1).get(0);
    }

    private java.util.List<HttpRequest> capturedRequests(int expected) throws Exception {
        ArgumentCaptor<HttpRequest> cap = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient, times(expected)).send(cap.capture(), any());
        return cap.getAllValues();
    }

    private static String chatEnvelope(String contentJson) throws Exception {
        return MAPPER.writeValueAsString(Map.of(
                "choices", List.of(Map.of("message", Map.of("content", contentJson)))
        ));
    }
}
