package com.readthekjv.controller;

import com.readthekjv.service.TtsService;
import com.readthekjv.service.VoiceCatalogService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;
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
    private final VoiceCatalogService voiceCatalog;

    public TtsController(TtsService ttsService, VoiceCatalogService voiceCatalog) {
        this.ttsService = ttsService;
        this.voiceCatalog = voiceCatalog;
    }

    /**
     * Voices a reader may choose between, default first, each with the verse id to
     * audition it with. A single entry means the picker has nothing to offer and the
     * client hides it — pregenerating a second voice is all it takes to light it up.
     */
    @GetMapping("/audio/voices")
    public ResponseEntity<Map<String, Object>> getVoices() {
        if (!ttsService.isEnabled()) {
            return ResponseEntity.notFound().build();
        }
        List<VoiceCatalogService.Voice> voices = voiceCatalog.selectableVoices();
        return ResponseEntity.ok(Map.of(
                "voices", voices,
                "default", ttsService.defaultVoice(),
                "sampleVerseId", VoiceCatalogService.SAMPLE_VERSE_ID));
    }

    /**
     * Resolves the requested voice, or null when the request named one that is not
     * selectable. An absent voice means "the server default", which is the only voice
     * allowed to generate; a named one is always serve-only.
     */
    private String resolveRequestedVoice(String voice) {
        if (voice == null || voice.isBlank()) {
            return ttsService.defaultVoice();
        }
        String normalized = voice.trim().toLowerCase(Locale.ROOT);
        return voiceCatalog.isSelectable(normalized) ? normalized : null;
    }

    private boolean isDefaultVoice(String voice) {
        return ttsService.defaultVoice().equals(voice);
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
            @RequestParam(required = false) String voice,
            @AuthenticationPrincipal UserDetails user) {
        // Validate verse ID range
        if (verseId < MIN_VERSE_ID || verseId > MAX_VERSE_ID) {
            return ResponseEntity.badRequest().build();
        }

        // Check if TTS is enabled
        if (!ttsService.isEnabled()) {
            return ResponseEntity.notFound().build();
        }

        String resolved = resolveRequestedVoice(voice);
        if (resolved == null) {
            return ResponseEntity.badRequest().build();
        }

        // Cache hit — public, no OpenAI spend. Prefetch only when signed in.
        Optional<String> cached = ttsService.findCachedAudioUrlForVerse(verseId, resolved);
        if (cached.isPresent()) {
            if (user != null && isDefaultVoice(resolved)) {
                ttsService.triggerPrefetch(verseId);
            }
            return ResponseEntity.ok(Map.of("url", cached.get()));
        }

        // A non-default voice is serve-only: it was offered because its corpus is
        // complete, so a miss is a gap to report, never a licence to spend.
        if (!isDefaultVoice(resolved)) {
            return ResponseEntity.notFound().build();
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
     * Get audio URL for a book announcement, spoken at a book break ahead of
     * the chapter announcement.
     *
     * @param book Book name (URL encoded)
     * @return JSON with url field or error status
     */
    @GetMapping("/audio/book/{book}")
    public ResponseEntity<Map<String, String>> getBookAudio(
            @PathVariable String book,
            @RequestParam(required = false) String voice,
            @AuthenticationPrincipal UserDetails user) {
        if (book == null || book.isBlank() || !ttsService.isKnownBook(book)) {
            return ResponseEntity.badRequest().build();
        }

        if (!ttsService.isEnabled()) {
            return ResponseEntity.notFound().build();
        }

        String resolved = resolveRequestedVoice(voice);
        if (resolved == null) {
            return ResponseEntity.badRequest().build();
        }

        Optional<String> cached = ttsService.findCachedAudioUrlForBook(book, resolved);
        if (cached.isPresent()) {
            return ResponseEntity.ok(Map.of("url", cached.get()));
        }

        if (!isDefaultVoice(resolved)) {
            return ResponseEntity.notFound().build();
        }

        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Sign in to generate audio"));
        }

        Optional<String> cdnUrl = ttsService.getAudioUrlForBook(book);
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
            @RequestParam(required = false) String voice,
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

        String resolved = resolveRequestedVoice(voice);
        if (resolved == null) {
            return ResponseEntity.badRequest().build();
        }

        Optional<String> cached = ttsService.findCachedAudioUrlForChapter(book, chapter, resolved);
        if (cached.isPresent()) {
            return ResponseEntity.ok(Map.of("url", cached.get()));
        }

        if (!isDefaultVoice(resolved)) {
            return ResponseEntity.notFound().build();
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
