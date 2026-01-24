package com.readthekjv.controller;

import com.readthekjv.service.TtsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

/**
 * REST controller for TTS audio endpoints.
 */
@RestController
@RequestMapping("/api")
public class TtsController {

    private static final int MIN_VERSE_ID = 1;
    private static final int MAX_VERSE_ID = 31102;

    private final TtsService ttsService;

    public TtsController(TtsService ttsService) {
        this.ttsService = ttsService;
    }

    /**
     * Get TTS status for feature detection.
     *
     * @return JSON object with enabled status
     */
    @GetMapping("/tts/status")
    public ResponseEntity<Map<String, Boolean>> getStatus() {
        return ResponseEntity.ok(Map.of("enabled", ttsService.isEnabled()));
    }

    /**
     * Get audio URL for a specific verse.
     * Returns JSON with the CDN URL.
     *
     * @param verseId Verse ID (1-31102)
     * @return JSON with url field or error status
     */
    @GetMapping("/audio/{verseId}")
    public ResponseEntity<Map<String, String>> getAudio(@PathVariable int verseId) {
        // Validate verse ID range
        if (verseId < MIN_VERSE_ID || verseId > MAX_VERSE_ID) {
            return ResponseEntity.badRequest().build();
        }

        // Check if TTS is enabled
        if (!ttsService.isEnabled()) {
            return ResponseEntity.notFound().build();
        }

        // Get CDN URL (generates if needed)
        Optional<String> cdnUrl = ttsService.getAudioUrlForVerse(verseId);
        if (cdnUrl.isEmpty()) {
            return ResponseEntity.internalServerError().build();
        }

        return ResponseEntity.ok(Map.of("url", cdnUrl.get()));
    }

    /**
     * Get audio URL for a chapter announcement.
     * Returns JSON with the CDN URL.
     *
     * @param book Book name (URL encoded)
     * @param chapter Chapter number
     * @return JSON with url field or error status
     */
    @GetMapping("/audio/chapter/{book}/{chapter}")
    public ResponseEntity<Map<String, String>> getChapterAudio(
            @PathVariable String book,
            @PathVariable int chapter) {
        // Basic validation
        if (book == null || book.isBlank() || chapter < 1 || chapter > 150) {
            return ResponseEntity.badRequest().build();
        }

        // Check if TTS is enabled
        if (!ttsService.isEnabled()) {
            return ResponseEntity.notFound().build();
        }

        // Get CDN URL (generates if needed)
        Optional<String> cdnUrl = ttsService.getAudioUrlForChapter(book, chapter);
        if (cdnUrl.isEmpty()) {
            return ResponseEntity.internalServerError().build();
        }

        return ResponseEntity.ok(Map.of("url", cdnUrl.get()));
    }
}
