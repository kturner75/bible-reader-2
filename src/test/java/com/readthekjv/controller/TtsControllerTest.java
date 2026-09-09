package com.readthekjv.controller;

import com.readthekjv.service.TtsService;
import com.readthekjv.service.VoiceCatalogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * H2: cache hits are public; generation requires an authenticated principal.
 */
class TtsControllerTest {

    private TtsService ttsService;
    private VoiceCatalogService voiceCatalog;
    private TtsController controller;
    private UserDetails user;

    @BeforeEach
    void setUp() {
        ttsService = mock(TtsService.class);
        voiceCatalog = mock(VoiceCatalogService.class);
        controller = new TtsController(ttsService, voiceCatalog);
        // Requests that name no voice resolve to the server default.
        when(ttsService.defaultVoice()).thenReturn("helios");
        user = User.withUsername("reader@example.com").password("x").roles("USER").build();
        when(ttsService.isEnabled()).thenReturn(true);
    }

    @Test
    void anonymousCacheHitReturnsUrlWithoutPrefetch() {
        when(ttsService.findCachedAudioUrlForVerse(1, "helios"))
                .thenReturn(Optional.of("https://cdn.example/audio/1.mp3"));

        ResponseEntity<Map<String, String>> res = controller.getAudio(1, null, null);

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertEquals("https://cdn.example/audio/1.mp3", res.getBody().get("url"));
        verify(ttsService, never()).triggerPrefetch(anyInt());
        verify(ttsService, never()).getAudioUrlForVerse(anyInt());
    }

    @Test
    void authenticatedCacheHitTriggersPrefetch() {
        when(ttsService.findCachedAudioUrlForVerse(1, "helios"))
                .thenReturn(Optional.of("https://cdn.example/audio/1.mp3"));

        ResponseEntity<Map<String, String>> res = controller.getAudio(1, null, user);

        assertEquals(HttpStatus.OK, res.getStatusCode());
        verify(ttsService).triggerPrefetch(1);
    }

    @Test
    void anonymousCacheMissRequiresAuth() {
        when(ttsService.findCachedAudioUrlForVerse(2, "helios")).thenReturn(Optional.empty());

        ResponseEntity<Map<String, String>> res = controller.getAudio(2, null, null);

        assertEquals(HttpStatus.UNAUTHORIZED, res.getStatusCode());
        verify(ttsService, never()).getAudioUrlForVerse(anyInt());
    }

    @Test
    void authenticatedCacheMissGenerates() {
        when(ttsService.findCachedAudioUrlForVerse(3, "helios")).thenReturn(Optional.empty());
        when(ttsService.getAudioUrlForVerse(3))
                .thenReturn(Optional.of("https://cdn.example/audio/3.mp3"));

        ResponseEntity<Map<String, String>> res = controller.getAudio(3, null, user);

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertEquals("https://cdn.example/audio/3.mp3", res.getBody().get("url"));
        verify(ttsService).getAudioUrlForVerse(3);
    }

    @Test
    void bookRejectsUnknownBook() {
        when(ttsService.isKnownBook("NotABook")).thenReturn(false);

        ResponseEntity<Map<String, String>> res = controller.getBookAudio("NotABook", null, user);

        assertEquals(HttpStatus.BAD_REQUEST, res.getStatusCode());
        verify(ttsService, never()).getAudioUrlForBook(any());
    }

    @Test
    void anonymousBookCacheMissRequiresAuth() {
        when(ttsService.isKnownBook("Exodus")).thenReturn(true);
        when(ttsService.findCachedAudioUrlForBook("Exodus", "helios")).thenReturn(Optional.empty());

        ResponseEntity<Map<String, String>> res = controller.getBookAudio("Exodus", null, null);

        assertEquals(HttpStatus.UNAUTHORIZED, res.getStatusCode());
        verify(ttsService, never()).getAudioUrlForBook(any());
    }

    @Test
    void anonymousBookCacheHitIsPublic() {
        when(ttsService.isKnownBook("Exodus")).thenReturn(true);
        when(ttsService.findCachedAudioUrlForBook("Exodus", "helios"))
                .thenReturn(Optional.of("https://cdn.example/audio/books/Exodus.mp3"));

        ResponseEntity<Map<String, String>> res = controller.getBookAudio("Exodus", null, null);

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertEquals("https://cdn.example/audio/books/Exodus.mp3", res.getBody().get("url"));
        verify(ttsService, never()).getAudioUrlForBook(any());
    }

    @Test
    void chapterRejectsUnknownBook() {
        when(ttsService.isKnownBook("NotABook")).thenReturn(false);

        ResponseEntity<Map<String, String>> res =
                controller.getChapterAudio("NotABook", 1, null, user);

        assertEquals(HttpStatus.BAD_REQUEST, res.getStatusCode());
        verify(ttsService, never()).getAudioUrlForChapter(any(), anyInt());
    }

    // ── Voice selection ───────────────────────────────────────────────────────

    @Test
    void anUnknownVoiceIsRejectedRatherThanGenerated() {
        when(voiceCatalog.isSelectable("nonesuch")).thenReturn(false);

        ResponseEntity<Map<String, String>> res = controller.getAudio(1, "nonesuch", user);

        // The whole point: an arbitrary voice id must not become a new namespace
        // that a signed-in request generates 31k clips into.
        assertEquals(HttpStatus.BAD_REQUEST, res.getStatusCode());
        verify(ttsService, never()).getAudioUrlForVerse(anyInt());
    }

    @Test
    void aSelectableNonDefaultVoiceIsServedFromCache() {
        when(voiceCatalog.isSelectable("ara")).thenReturn(true);
        when(ttsService.findCachedAudioUrlForVerse(1, "ara"))
                .thenReturn(Optional.of("https://cdn.example/audio/xai/ara/verses/0/1.mp3"));

        ResponseEntity<Map<String, String>> res = controller.getAudio(1, "ara", null);

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertEquals("https://cdn.example/audio/xai/ara/verses/0/1.mp3", res.getBody().get("url"));
    }

    @Test
    void aNonDefaultVoiceNeverGeneratesOnAMiss() {
        when(voiceCatalog.isSelectable("ara")).thenReturn(true);
        when(ttsService.findCachedAudioUrlForVerse(1, "ara")).thenReturn(Optional.empty());

        // Signed in, so the default voice would generate here. A selected voice
        // is serve-only — it was offered because its corpus is already complete.
        ResponseEntity<Map<String, String>> res = controller.getAudio(1, "ara", user);

        assertEquals(HttpStatus.NOT_FOUND, res.getStatusCode());
        verify(ttsService, never()).getAudioUrlForVerse(anyInt());
    }

    @Test
    void prefetchIsOnlyTriggeredForTheDefaultVoice() {
        when(voiceCatalog.isSelectable("ara")).thenReturn(true);
        when(ttsService.findCachedAudioUrlForVerse(1, "ara"))
                .thenReturn(Optional.of("https://cdn.example/a.mp3"));

        controller.getAudio(1, "ara", user);

        // Prefetch generates ahead; it must not run for a serve-only voice.
        verify(ttsService, never()).triggerPrefetch(anyInt());
    }

    @Test
    void announcementsHonourTheSelectedVoiceToo() {
        when(voiceCatalog.isSelectable("ara")).thenReturn(true);
        when(ttsService.isKnownBook("Exodus")).thenReturn(true);
        when(ttsService.isKnownBook("Genesis")).thenReturn(true);
        when(ttsService.findCachedAudioUrlForBook("Exodus", "ara"))
                .thenReturn(Optional.of("https://cdn.example/books/Exodus.mp3"));
        when(ttsService.findCachedAudioUrlForChapter("Genesis", 1, "ara"))
                .thenReturn(Optional.of("https://cdn.example/chapters/1.mp3"));

        assertEquals(HttpStatus.OK, controller.getBookAudio("Exodus", "ara", null).getStatusCode());
        assertEquals(HttpStatus.OK, controller.getChapterAudio("Genesis", 1, "ara", null).getStatusCode());
    }
}
