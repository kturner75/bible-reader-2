package com.readthekjv.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WhisperServiceTest {

    private XaiOAuthTokenManager oauth;
    private WhisperService service;
    private HttpClient httpClient;

    @BeforeEach
    void setUp() {
        oauth = mock(XaiOAuthTokenManager.class);
        when(oauth.getAccessToken()).thenReturn(Optional.empty());
        when(oauth.isConfigured()).thenReturn(false);
        service = new WhisperService(oauth);
        httpClient = mock(HttpClient.class);
        ReflectionTestUtils.setField(service, "httpClient", httpClient);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "provider", "xai");
        ReflectionTestUtils.setField(service, "openAiKey", "sk-openai");
        ReflectionTestUtils.setField(service, "xaiKey", "xai-key");
    }

    @Test
    void resolvedBearer_prefersOAuthAccessTokenOverApiKey() {
        when(oauth.getAccessToken()).thenReturn(Optional.of("oauth-access-token"));
        assertEquals("oauth-access-token", service.resolvedBearer());
    }

    @Test
    void resolvedBearer_fallsBackToXaiKeyWhenOAuthEmpty() {
        when(oauth.getAccessToken()).thenReturn(Optional.empty());
        assertEquals("xai-key", service.resolvedBearer());
    }

    @Test
    void openaiProviderIgnoresXaiOAuthToken() {
        ReflectionTestUtils.setField(service, "provider", "openai");
        when(oauth.getAccessToken()).thenReturn(Optional.of("oauth-access-token"));
        assertEquals("sk-openai", service.resolvedBearer());
    }

    @Test
    void isEnabled_xaiOAuthConfiguredWithoutApiKey() {
        ReflectionTestUtils.setField(service, "xaiKey", "");
        when(oauth.isConfigured()).thenReturn(true);
        assertTrue(service.isEnabled());
    }

    @Test
    void isEnabled_xaiNeitherOAuthNorKey() {
        ReflectionTestUtils.setField(service, "xaiKey", "");
        when(oauth.isConfigured()).thenReturn(false);
        assertFalse(service.isEnabled());
    }

    @Test
    void transcribe_usesOAuthBearerWhenPresent() throws Exception {
        when(oauth.getAccessToken()).thenReturn(Optional.of("oauth-access-token"));
        stubHttp(200, "{\"text\":\"hello\"}");

        service.transcribe("audio".getBytes(StandardCharsets.UTF_8), "audio/webm", "hint");

        HttpRequest sent = capturedRequest();
        assertEquals("https://api.x.ai/v1/stt", sent.uri().toString());
        assertEquals("Bearer oauth-access-token", sent.headers().firstValue("Authorization").orElseThrow());
    }

    @Test
    void transcribe_fallsBackToXaiKeyWhenOAuthEmpty() throws Exception {
        when(oauth.getAccessToken()).thenReturn(Optional.empty());
        stubHttp(200, "{\"text\":\"hello\"}");

        service.transcribe("audio".getBytes(StandardCharsets.UTF_8), "audio/webm", "hint");

        assertEquals("Bearer xai-key", capturedRequest().headers().firstValue("Authorization").orElseThrow());
    }

    @SuppressWarnings("unchecked")
    private void stubHttp(int status, String body) throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
    }

    private HttpRequest capturedRequest() throws Exception {
        var cap = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        org.mockito.Mockito.verify(httpClient).send(cap.capture(), any());
        return cap.getValue();
    }
}
