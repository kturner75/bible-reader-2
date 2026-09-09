package com.readthekjv.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The completeness gate is the whole security and cost story for user-selectable
 * voices, so it is worth testing against a real Bible rather than a fixture.
 */
class VoiceCatalogServiceTest {

    private BibleService bible;
    private TtsService tts;
    private VoiceCatalogService catalog;
    private Set<String> bucketKeys = Set.of();

    @BeforeEach
    void setUp() throws Exception {
        bible = new BibleService();
        new BibleDataLoader(bible, mock(LuceneIndexService.class), new ObjectMapper()).loadData();
        // Subclassed rather than swapped later: the catalog reads provider and voice
        // off this instance, so a stand-in must carry the same configuration.
        tts = new TtsService(bible, mock(XaiOAuthTokenManager.class)) {
            @Override
            public Set<String> allAudioKeys() {
                return bucketKeys;
            }
        };
        ReflectionTestUtils.setField(tts, "provider", "xai");
        ReflectionTestUtils.setField(tts, "voice", "helios");
        ReflectionTestUtils.setField(tts, "audioPrefix", "audio");
        catalog = new VoiceCatalogService(tts, bible);
        ReflectionTestUtils.setField(catalog, "cacheSeconds", 300L);
    }

    /** Every key a complete corpus for {@code voice} would contain. */
    private Set<String> fullCorpus(String voice) {
        Set<String> keys = new HashSet<>();
        for (int id = 1; id <= bible.getTotalVerses(); id++) {
            keys.add(tts.getVerseKey(id, voice));
        }
        bible.getBooks().forEach(b -> {
            keys.add(tts.getBookKey(b.name(), voice));
            bible.getChapters(b.id()).forEach(c ->
                    keys.add(tts.getChapterKey(b.name(), c.chapter(), voice)));
        });
        return keys;
    }

    @Test
    void expectedCountsMatchTheCorpusThePregenActuallyPlans() {
        // 31,102 + 66 + 216 — but derived, so it cannot drift from the Bible data.
        assertEquals(31384, fullCorpus("helios").size());
    }

    @Test
    void aFullyGeneratedVoiceIsComplete() {
        stubKeys(fullCorpus("ara"));
        assertTrue(catalog.completeVoices().contains("ara"));
    }

    @Test
    void oneMissingClipIsEnoughToWithholdAVoice() {
        Set<String> corpus = fullCorpus("ara");
        corpus.remove(tts.getVerseKey(17000, "ara"));
        stubKeys(corpus);

        // A voice offered with a hole in it would 401 mid-chapter for a signed-out
        // reader and stop the reading — so "nearly complete" must not qualify.
        assertFalse(catalog.completeVoices().contains("ara"));
    }

    @Test
    void aMissingAnnouncementAlsoWithholdsTheVoice() {
        Set<String> corpus = fullCorpus("ara");
        corpus.remove(tts.getBookKey("Jude", "ara"));
        stubKeys(corpus);
        assertFalse(catalog.completeVoices().contains("ara"));

        Set<String> noPsalm = fullCorpus("ara");
        noPsalm.remove(tts.getChapterKey("Psalm", 150, "ara"));
        stubKeys(noPsalm);
        assertFalse(catalog.completeVoices().contains("ara"));
    }

    @Test
    void theConfiguredVoiceIsOfferedEvenWhileItsCorpusIsStillFilling() {
        stubKeys(Set.of(tts.getVerseKey(1, "helios")));

        List<VoiceCatalogService.Voice> voices = catalog.selectableVoices();

        // helios is the one voice allowed to generate on demand, so a gap is a
        // pause rather than a dead end — it stays selectable, and stays first.
        assertEquals(1, voices.size());
        assertEquals("helios", voices.get(0).id());
        assertTrue(voices.get(0).isDefault());
        assertFalse(catalog.completeVoices().contains("helios"));
    }

    @Test
    void anIncompleteVoiceIsNotSelectableSoItCanNeverBeGeneratedInto() {
        Set<String> corpus = fullCorpus("ara");
        corpus.remove(tts.getVerseKey(1, "ara"));
        stubKeys(corpus);

        assertFalse(catalog.isSelectable("ara"));
        assertTrue(catalog.isSelectable("helios"));
        assertFalse(catalog.isSelectable("nonesuch"));
        assertFalse(catalog.isSelectable(null));
    }

    @Test
    void aCompleteSecondVoiceBecomesSelectableWithNoCodeChange() {
        Set<String> keys = new HashSet<>(fullCorpus("helios"));
        keys.addAll(fullCorpus("ara"));
        stubKeys(keys);

        List<String> ids = catalog.selectableVoices().stream()
                .map(VoiceCatalogService.Voice::id).toList();

        assertEquals(List.of("helios", "ara"), ids, "default leads, then the rest");
        assertTrue(catalog.isSelectable("ara"));
        assertTrue(catalog.isSelectable("ARA"), "ids are case-insensitive");
    }

    /** Replaces the bucket listing without touching S3. */
    private void stubKeys(Set<String> keys) {
        bucketKeys = keys;
        catalog.invalidate();
    }

    @Test
    void staleKeysCannotPadAVoiceIntoLookingComplete() {
        Set<String> corpus = fullCorpus("ara");
        corpus.remove(tts.getVerseKey(17000, "ara"));
        // A leftover from an older layout — this bucket carried exactly this kind of
        // orphan (per-book chapter objects) until they were deleted. Counting objects
        // per family would let it stand in for the verse that is actually missing.
        corpus.add("audio/xai/ara/verses/17/obsolete.mp3");
        stubKeys(corpus);

        assertFalse(catalog.completeVoices().contains("ara"));
        assertFalse(catalog.isSelectable("ara"));
    }

    @Test
    void aStaleChapterObjectCannotSubstituteForAMissingOne() {
        Set<String> corpus = fullCorpus("ara");
        corpus.remove(tts.getChapterKey("Genesis", 40, "ara"));
        corpus.add("audio/xai/ara/chapters/Genesis_40.mp3");   // the pre-collapse key
        stubKeys(corpus);

        assertFalse(catalog.completeVoices().contains("ara"));
    }

    @Test
    void extraObjectsAreHarmlessWhenNothingIsMissing() {
        Set<String> corpus = fullCorpus("ara");
        corpus.add("audio/xai/ara/chapters/Genesis_40.mp3");
        corpus.add("audio/xai/ara/verses/17/obsolete.mp3");
        stubKeys(corpus);

        // Completeness asks "is everything required present", not "is nothing else".
        assertTrue(catalog.completeVoices().contains("ara"));
    }
}
