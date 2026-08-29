package com.readthekjv.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Speech-to-text transcription via a configurable provider (OpenAI or xAI).
 * Set {@code STT_PROVIDER=xai} to switch providers. xAI calls use a SuperGrok
 * OAuth access token when {@link XaiOAuthTokenManager} has one, otherwise
 * {@code XAI_API_KEY}.
 */
@Service
public class WhisperService {

    private static final Logger log = LoggerFactory.getLogger(WhisperService.class);
    private static final int MAX_HINT_CHARS = 900; // safely below Whisper's ~224-token prompt limit

    private static final String OPENAI_URL = "https://api.openai.com/v1/audio/transcriptions";
    private static final String OPENAI_MODEL = "whisper-1";

    private static final String XAI_URL = "https://api.x.ai/v1/stt";

    @Value("${tts.enabled:false}")
    private boolean enabled;

    @Value("${stt.provider:openai}")
    private String provider;

    @Value("${OPENAI_API_KEY:}")
    private String openAiKey;

    @Value("${XAI_API_KEY:}")
    private String xaiKey;

    private final XaiOAuthTokenManager xaiOAuthTokenManager;
    private HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WhisperService(XaiOAuthTokenManager xaiOAuthTokenManager) {
        this.xaiOAuthTokenManager = xaiOAuthTokenManager;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    public boolean isEnabled() {
        if (!enabled) {
            return false;
        }
        // isConfigured() is a local check — do not refresh a token just to report availability.
        if (isXai() && xaiOAuthTokenManager != null && xaiOAuthTokenManager.isConfigured()) {
            return true;
        }
        String key = resolvedKey();
        return key != null && !key.isBlank();
    }

    /**
     * Transcribes audio bytes using Whisper.
     *
     * @param audioBytes  raw audio data from the browser MediaRecorder
     * @param contentType MIME type (e.g. "audio/webm;codecs=opus", "audio/mp4")
     * @param hint        expected passage text — passed as Whisper prompt to improve KJV vocabulary accuracy
     * @return plain-text transcript
     * @throws IOException          on HTTP or I/O error
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    private boolean isXai() {
        return "xai".equalsIgnoreCase(provider);
    }

    private String resolvedUrl() {
        return isXai() ? XAI_URL : OPENAI_URL;
    }

    private String resolvedModel() {
        return isXai() ? null : OPENAI_MODEL;
    }

    String resolvedKey() {
        return isXai() ? xaiKey : openAiKey;
    }

    /**
     * Bearer for the STT call: SuperGrok OAuth access token when xAI and
     * present, otherwise the static provider API key.
     */
    String resolvedBearer() {
        if (isXai() && xaiOAuthTokenManager != null) {
            var oauth = xaiOAuthTokenManager.getAccessToken();
            if (oauth.isPresent() && !oauth.get().isBlank()) {
                return oauth.get();
            }
        }
        return resolvedKey();
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
        var refreshed = xaiOAuthTokenManager.getAccessToken();
        if (refreshed.isPresent() && !refreshed.get().isBlank() && !refreshed.get().equals(rejectedBearer)) {
            return refreshed.get();
        }
        String key = resolvedKey();
        if (key != null && !key.isBlank() && !key.equals(rejectedBearer)) {
            return key;
        }
        return null;
    }

    private HttpResponse<String> sendStt(byte[] body, String boundary, String bearer)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(resolvedUrl()))
                .header("Authorization", "Bearer " + bearer)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .timeout(Duration.ofSeconds(120)) // STT is slower than TTS
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    public String transcribe(byte[] audioBytes, String contentType, String hint)
            throws IOException, InterruptedException {

        String boundary = "----WhisperBoundary" + System.nanoTime();
        byte[] body = buildMultipartBody(boundary, audioBytes, contentType, hint);

        String bearer = resolvedBearer();
        if (bearer == null || bearer.isBlank()) {
            throw new IOException("STT unavailable: no OAuth access token and no API key configured");
        }

        log.debug("STT provider={} url={}", provider, resolvedUrl());

        HttpResponse<String> response = sendStt(body, boundary, bearer);
        if (isXai() && response.statusCode() == 401 && wasOAuthBearer(bearer)) {
            xaiOAuthTokenManager.invalidate();
            String retryBearer = retryXaiBearer(bearer);
            if (retryBearer != null) {
                log.warn("event=xai_oauth_rejected retrying_stt");
                response = sendStt(body, boundary, retryBearer);
            }
        }

        if (response.statusCode() != 200) {
            log.error("Whisper API error: {} — {}", response.statusCode(), response.body());
            throw new IOException("Whisper API error: " + response.statusCode());
        }

        // xAI returns JSON; OpenAI returns plain text (response_format=text)
        if (isXai()) {
            JsonNode node = objectMapper.readTree(response.body());
            return node.path("text").asText().trim();
        }
        return response.body().trim();
    }

    /**
     * Builds a multipart/form-data body manually.
     * CRITICAL: pw.flush() must be called before writing binary audio bytes directly
     * to the underlying ByteArrayOutputStream — PrintWriter buffers internally.
     */
    private byte[] buildMultipartBody(String boundary, byte[] audioBytes,
                                      String contentType, String hint) throws IOException {

        // Derive file extension and clean content-type (Whisper rejects "audio/webm;codecs=opus")
        String cleanContentType = contentType != null && contentType.contains(";")
                ? contentType.substring(0, contentType.indexOf(';')).trim()
                : (contentType != null ? contentType : "audio/webm");

        String ext;
        if (cleanContentType.contains("ogg")) {
            ext = "ogg";
        } else if (cleanContentType.contains("mp4") || cleanContentType.contains("m4a")) {
            ext = "mp4";
        } else {
            ext = "webm";
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintWriter pw = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), true);

        // Part: model (xAI doesn't accept this field)
        if (resolvedModel() != null) {
            pw.print("--" + boundary + "\r\n");
            pw.print("Content-Disposition: form-data; name=\"model\"\r\n\r\n");
            pw.print(resolvedModel() + "\r\n");
        }

        // Part: response_format=text (plain transcript, not JSON)
        pw.print("--" + boundary + "\r\n");
        pw.print("Content-Disposition: form-data; name=\"response_format\"\r\n\r\n");
        pw.print("text\r\n");

        // Part: language=en (KJV is always English)
        pw.print("--" + boundary + "\r\n");
        pw.print("Content-Disposition: form-data; name=\"language\"\r\n\r\n");
        pw.print("en\r\n");

        // Part: prompt (context hint improves accuracy for KJV archaic vocabulary)
        if (hint != null && !hint.isBlank()) {
            String safeHint = hint.length() > MAX_HINT_CHARS
                    ? hint.substring(0, MAX_HINT_CHARS)
                    : hint;
            pw.print("--" + boundary + "\r\n");
            pw.print("Content-Disposition: form-data; name=\"prompt\"\r\n\r\n");
            pw.print(safeHint + "\r\n");
        }

        // Part: file (binary audio — flush pw before writing bytes directly to out)
        pw.print("--" + boundary + "\r\n");
        pw.print("Content-Disposition: form-data; name=\"file\"; filename=\"audio." + ext + "\"\r\n");
        pw.print("Content-Type: " + cleanContentType + "\r\n\r\n");
        pw.flush(); // CRITICAL: flush buffered text before writing binary data

        out.write(audioBytes);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));

        // Closing boundary
        pw.print("--" + boundary + "--\r\n");
        pw.flush();

        return out.toByteArray();
    }
}
