package com.readthekjv.service;

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
 * Set STT_PROVIDER=xai and STT_API_KEY=&lt;your-xai-key&gt; to switch providers.
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

    private final HttpClient httpClient;

    public WhisperService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    public boolean isEnabled() {
        return enabled && resolvedKey() != null && !resolvedKey().isBlank();
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

    private String resolvedKey() {
        return isXai() ? xaiKey : openAiKey;
    }

    public String transcribe(byte[] audioBytes, String contentType, String hint)
            throws IOException, InterruptedException {

        String boundary = "----WhisperBoundary" + System.nanoTime();
        byte[] body = buildMultipartBody(boundary, audioBytes, contentType, hint);

        log.debug("STT provider={} url={}", provider, resolvedUrl());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(resolvedUrl()))
                .header("Authorization", "Bearer " + resolvedKey())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .timeout(Duration.ofSeconds(120)) // STT is slower than TTS
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            log.error("Whisper API error: {} — {}", response.statusCode(), response.body());
            throw new IOException("Whisper API error: " + response.statusCode());
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
