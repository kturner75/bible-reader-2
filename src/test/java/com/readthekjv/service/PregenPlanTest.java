package com.readthekjv.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class PregenPlanTest {

    private TtsPregenService pregen;

    @BeforeEach
    void setUp() throws Exception {
        BibleService bible = new BibleService();
        new BibleDataLoader(bible, mock(LuceneIndexService.class),
                new com.fasterxml.jackson.databind.ObjectMapper()).loadData();
        TtsService tts = new TtsService(bible, mock(XaiOAuthTokenManager.class));
        ReflectionTestUtils.setField(tts, "provider", "xai");
        ReflectionTestUtils.setField(tts, "voice", "helios");
        ReflectionTestUtils.setField(tts, "audioPrefix", "audio");
        pregen = new TtsPregenService(tts, bible,
                mock(org.springframework.context.ConfigurableApplicationContext.class));
        ReflectionTestUtils.setField(pregen, "scope", "all");
    }

    @Test
    void planCoversTheWholeCorpusExactlyOnce() {
        List<TtsPregenService.Clip> plan = pregen.plan();
        Map<String, List<TtsPregenService.Clip>> byKind = plan.stream()
                .collect(Collectors.groupingBy(c ->
                        c.key().contains("/books/") ? "book"
                                : c.key().contains("/chapters/") ? "chapter" : "verse"));

        System.out.println("books=" + byKind.get("book").size()
                + " chapters=" + byKind.get("chapter").size()
                + " verses=" + byKind.get("verse").size()
                + " total=" + plan.size());
        System.out.println("namespace sample: " + plan.get(0).key() + "  <- " + plan.get(0).text());
        byKind.get("chapter").stream().limit(3).forEach(c ->
                System.out.println("  " + c.key() + "  <- " + c.text()));
        byKind.get("chapter").stream()
                .filter(c -> c.key().contains("psalm")).limit(2)
                .forEach(c -> System.out.println("  " + c.key() + "  <- " + c.text()));

        assertEquals(66, byKind.get("book").size(), "one clip per book");
        assertEquals(216, byKind.get("chapter").size(), "66 generic + 150 psalm");
        assertEquals(31102, byKind.get("verse").size(), "every verse");
        assertEquals(plan.size(), plan.stream().map(TtsPregenService.Clip::key).distinct().count(),
                "no key generated twice");
        assertTrue(plan.stream().allMatch(c -> c.key().startsWith("audio/xai/helios/")));
    }

    // ── Exit code honesty ─────────────────────────────────────────────────────

    @Test
    void onlyAGaplessRunCountsAsSuccess() {
        assertTrue(TtsPregenService.runSucceeded(true, 100, 100));

        // Clips that failed — the ursa run's 18 GOAWAY resets.
        assertFalse(TtsPregenService.runSucceeded(true, 82, 100));

        // Timed out with work outstanding. shutdownNow() interrupts workers that
        // return without incrementing anything, so the counters look clean here —
        // this is the case the failure count alone cannot see.
        assertFalse(TtsPregenService.runSucceeded(false, 100, 100));
        assertFalse(TtsPregenService.runSucceeded(false, 60, 100));
    }

    @Test
    void anEmptyRunIsStillASuccess() {
        // "Nothing to do — corpus is complete" must not exit non-zero.
        assertTrue(TtsPregenService.runSucceeded(true, 0, 0));
    }
}
