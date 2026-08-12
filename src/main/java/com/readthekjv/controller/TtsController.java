package com.readthekjv.controller;

import com.readthekjv.service.TtsService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

/**
 * REST controller for TTS audio endpoints.
 *
 * <p>H2: Pre-generated CDN objects are public (cache hit). On-demand OpenAI
 * generation + prefetch require an authenticated session.
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
    public ResponseEntity<Map<String, String>> getAudio(
            @PathVariable int verseId,
            @AuthenticationPrincipal UserDetails user) {
        // Validate verse ID range
        if (verseId < MIN_VERSE_ID || verseId > MAX_VERSE_ID) {
            return ResponseEntity.badRequest().build();
        }

        // Check if TTS is enabled
        if (!ttsService.isEnabled()) {
            return ResponseEntity.notFound().build();
        }

        // Cache hit — public, no OpenAI spend. Prefetch only when signed in.
        Optional<String> cached = ttsService.findCachedAudioUrlForVerse(verseId);
        if (cached.isPresent()) {
            if (user != null) {
                ttsService.triggerPrefetch(verseId);
            }
            return ResponseEntity.ok(Map.of("url", cached.get()));
        }

        // Cache miss — generation requires auth (H2)
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Sign in to generate audio"));
        }

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
            @PathVariable int chapter,
            @AuthenticationPrincipal UserDetails user) {
        // Basic validation + allowlist known books (closes L2 while touching this path)
        if (book == null || book.isBlank() || chapter < 1 || chapter > 150
                || !ttsService.isKnownBook(book)) {
            return ResponseEntity.badRequest().build();
        }

        // Check if TTS is enabled
        if (!ttsService.isEnabled()) {
            return ResponseEntity.notFound().build();
        }

        Optional<String> cached = ttsService.findCachedAudioUrlForChapter(book, chapter);
        if (cached.isPresent()) {
            return ResponseEntity.ok(Map.of("url", cached.get()));
        }

        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Sign in to generate audio"));
        }

        Optional<String> cdnUrl = ttsService.getAudioUrlForChapter(book, chapter);
        if (cdnUrl.isEmpty()) {
            return ResponseEntity.internalServerError().build();
        }

        return ResponseEntity.ok(Map.of("url", cdnUrl.get()));
    }
}
