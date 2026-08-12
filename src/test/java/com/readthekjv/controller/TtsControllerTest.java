package com.readthekjv.controller;

import com.readthekjv.service.TtsService;
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
    private TtsController controller;
    private UserDetails user;

    @BeforeEach
    void setUp() {
        ttsService = mock(TtsService.class);
        controller = new TtsController(ttsService);
        user = User.withUsername("reader@example.com").password("x").roles("USER").build();
        when(ttsService.isEnabled()).thenReturn(true);
    }

    @Test
    void anonymousCacheHitReturnsUrlWithoutPrefetch() {
        when(ttsService.findCachedAudioUrlForVerse(1))
                .thenReturn(Optional.of("https://cdn.example/audio/1.mp3"));

        ResponseEntity<Map<String, String>> res = controller.getAudio(1, null);

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertEquals("https://cdn.example/audio/1.mp3", res.getBody().get("url"));
        verify(ttsService, never()).triggerPrefetch(anyInt());
        verify(ttsService, never()).getAudioUrlForVerse(anyInt());
    }

    @Test
    void authenticatedCacheHitTriggersPrefetch() {
        when(ttsService.findCachedAudioUrlForVerse(1))
                .thenReturn(Optional.of("https://cdn.example/audio/1.mp3"));

        ResponseEntity<Map<String, String>> res = controller.getAudio(1, user);

        assertEquals(HttpStatus.OK, res.getStatusCode());
        verify(ttsService).triggerPrefetch(1);
    }

    @Test
    void anonymousCacheMissRequiresAuth() {
        when(ttsService.findCachedAudioUrlForVerse(2)).thenReturn(Optional.empty());

        ResponseEntity<Map<String, String>> res = controller.getAudio(2, null);

        assertEquals(HttpStatus.UNAUTHORIZED, res.getStatusCode());
        verify(ttsService, never()).getAudioUrlForVerse(anyInt());
    }

    @Test
    void authenticatedCacheMissGenerates() {
        when(ttsService.findCachedAudioUrlForVerse(3)).thenReturn(Optional.empty());
        when(ttsService.getAudioUrlForVerse(3))
                .thenReturn(Optional.of("https://cdn.example/audio/3.mp3"));

        ResponseEntity<Map<String, String>> res = controller.getAudio(3, user);

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertEquals("https://cdn.example/audio/3.mp3", res.getBody().get("url"));
        verify(ttsService).getAudioUrlForVerse(3);
    }

    @Test
    void chapterRejectsUnknownBook() {
        when(ttsService.isKnownBook("NotABook")).thenReturn(false);

        ResponseEntity<Map<String, String>> res =
                controller.getChapterAudio("NotABook", 1, user);

        assertEquals(HttpStatus.BAD_REQUEST, res.getStatusCode());
        verify(ttsService, never()).getAudioUrlForChapter(any(), anyInt());
    }
}
