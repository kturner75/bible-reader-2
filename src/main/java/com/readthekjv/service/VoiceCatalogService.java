package com.readthekjv.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which voices a reader may choose between.
 *
 * <p><strong>A voice is offerable only when its corpus is complete.</strong> That
 * single rule does three jobs at once, which is why it is the gate rather than a
 * hand-maintained list:
 *
 * <ul>
 *   <li>It closes the spend vector. Cache hits are public but misses require auth
 *       and generate, so an arbitrary user-supplied voice id would otherwise be an
 *       invitation to drive 31k clips of generation into a brand-new namespace.
 *       A complete voice can only ever be a cache hit.</li>
 *   <li>It fixes the signed-out cliff. A half-generated voice would 401 mid-chapter
 *       and stop the reading; completeness makes that unrepresentable.</li>
 *   <li>It maintains itself. Pregenerate a voice and it appears; there is no roster
 *       to update in code, matching the standing "do not bake the roster into code"
 *       note in application.properties.</li>
 * </ul>
 *
 * <p>Display names and gender come from xAI's own roster endpoint, fetched lazily
 * and cached. If that call fails the catalog still works — the id is title-cased
 * and gender is omitted. Losing a label must never cost the reader the feature.
 */
@Service
public class VoiceCatalogService {

    private static final Logger log = LoggerFactory.getLogger(VoiceCatalogService.class);
    private static final String XAI_VOICES_URL = "https://api.x.ai/v1/tts/voices";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Genesis 1:1 — the sample a reader auditions a voice with. Reusing a verse that
     * every complete voice is guaranteed to have beats a bespoke sample asset: no
     * extra key family, no extra generation, and nothing that can drift out of sync
     * with the corpus.
     */
    public static final int SAMPLE_VERSE_ID = 1;

    private final TtsService ttsService;
    private final BibleService bibleService;
    private HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** Completeness is a bucket-wide LIST; too expensive per request, cheap every few minutes. */
    @Value("${tts.voices.cache-seconds:300}")
    private long cacheSeconds;

    private final AtomicReference<Cached> cache = new AtomicReference<>();
    private final AtomicReference<Map<String, Roster>> roster = new AtomicReference<>();

    public VoiceCatalogService(TtsService ttsService, BibleService bibleService) {
        this.ttsService = ttsService;
        this.bibleService = bibleService;
    }

    /**
     * What "complete" means, derived from the Bible itself rather than written down.
     * Hardcoding 31,102 / 66 / 216 would be three constants free to drift from the
     * corpus they describe — and the chapter figure is not even obvious, since
     * chapter clips collapse across books and the Psalms split off.
     */
    private int[] expectedCounts() {
        int chapters = (int) bibleService.getBooks().stream()
                .flatMap(b -> bibleService.getChapters(b.id()).stream()
                        .map(c -> ttsService.getChapterKey(b.name(), c.chapter())))
                .distinct()
                .count();
        return new int[] { bibleService.getTotalVerses(), bibleService.getBooks().size(), chapters };
    }

    /** One selectable voice. */
    public record Voice(String id, String name, String gender, boolean isDefault) {}

    private record Cached(List<Voice> voices, Instant at) {}

    private record Roster(String name, String gender) {}

    /**
     * Voices a reader may pick, default first. Never empty in practice: the
     * configured voice is always included, complete or not, because it is what the
     * server generates in and what a reader hears if they express no preference.
     */
    public List<Voice> selectableVoices() {
        Cached c = cache.get();
        if (c != null && c.at().isAfter(Instant.now().minusSeconds(Math.max(1, cacheSeconds)))) {
            return c.voices();
        }
        List<Voice> fresh = computeSelectableVoices();
        cache.set(new Cached(fresh, Instant.now()));
        return fresh;
    }

    /** True when {@code voice} is one a request may ask for. */
    public boolean isSelectable(String voice) {
        if (voice == null || voice.isBlank()) {
            return false;
        }
        String normalized = voice.trim().toLowerCase(Locale.ROOT);
        return selectableVoices().stream().anyMatch(v -> v.id().equals(normalized));
    }

    private List<Voice> computeSelectableVoices() {
        String defaultVoice = ttsService.defaultVoice();
        Set<String> complete = completeVoices();
        // The configured voice is always offered even when its corpus has gaps — it is
        // the one voice allowed to generate on demand, so a gap is a pause, not a 401.
        LinkedHashMap<String, Voice> out = new LinkedHashMap<>();
        out.put(defaultVoice, describe(defaultVoice, true));
        complete.stream().sorted()
                .filter(v -> !v.equals(defaultVoice))
                .forEach(v -> out.put(v, describe(v, false)));
        return List.copyOf(out.values());
    }

    /**
     * Voices whose corpus is fully present. One LIST of the whole audio prefix covers
     * every voice at once, so the cost does not grow with the number of voices.
     */
    Set<String> completeVoices() {
        String prefix = ttsService.audioPrefixValue() + "/" + ttsService.providerSegment() + "/";
        Pattern key = Pattern.compile(
                Pattern.quote(prefix) + "([^/]+)/(verses|books|chapters)/");
        Map<String, int[]> counts = new HashMap<>();
        for (String k : ttsService.allAudioKeys()) {
            Matcher m = key.matcher(k);
            if (!m.lookingAt()) continue;
            int[] c = counts.computeIfAbsent(m.group(1), v -> new int[3]);
            switch (m.group(2)) {
                case "verses" -> c[0]++;
                case "books" -> c[1]++;
                default -> c[2]++;
            }
        }
        int[] expected = expectedCounts();
        Set<String> complete = new java.util.LinkedHashSet<>();
        counts.forEach((v, c) -> {
            if (c[0] >= expected[0] && c[1] >= expected[1] && c[2] >= expected[2]) {
                complete.add(v);
            } else {
                log.debug("Voice {} incomplete: {}/{} verses, {}/{} books, {}/{} chapters",
                        v, c[0], expected[0], c[1], expected[1], c[2], expected[2]);
            }
        });
        return complete;
    }

    private Voice describe(String id, boolean isDefault) {
        Roster r = xaiRoster().get(id);
        return new Voice(id, r != null ? r.name() : titleCase(id), r != null ? r.gender() : null, isDefault);
    }

    private static String titleCase(String id) {
        if (id == null || id.isEmpty()) return id;
        return Character.toUpperCase(id.charAt(0)) + id.substring(1);
    }

    /**
     * xAI's voice roster, fetched once and cached for the process. Failure is not an
     * error worth surfacing — the catalog degrades to title-cased ids.
     */
    private Map<String, Roster> xaiRoster() {
        Map<String, Roster> cached = roster.get();
        if (cached != null) {
            return cached;
        }
        Map<String, Roster> loaded = new HashMap<>();
        try {
            String bearer = ttsService.metadataBearer();
            if (bearer != null && !bearer.isBlank()) {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(XAI_VOICES_URL))
                        .header("Authorization", "Bearer " + bearer)
                        .GET()
                        .timeout(Duration.ofSeconds(10))
                        .build();
                HttpResponse<String> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    JsonNode voices = MAPPER.readTree(response.body()).path("voices");
                    for (JsonNode v : voices) {
                        String id = v.path("voice_id").asText("").toLowerCase(Locale.ROOT);
                        if (!id.isBlank()) {
                            loaded.put(id, new Roster(
                                    v.path("name").asText(titleCase(id)),
                                    v.path("gender").asText(null)));
                        }
                    }
                } else {
                    log.info("xAI voice roster unavailable (HTTP {}) — using ids as labels",
                            response.statusCode());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("xAI voice roster fetch interrupted — using ids as labels");
        } catch (Exception e) {
            log.info("xAI voice roster unavailable ({}) — using ids as labels", e.toString());
        }
        roster.set(loaded);
        return loaded;
    }

    /** Drops the completeness cache so a just-finished pregen shows up immediately. */
    public void invalidate() {
        cache.set(null);
    }
}
