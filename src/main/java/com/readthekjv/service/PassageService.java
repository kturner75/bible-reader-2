package com.readthekjv.service;

import com.readthekjv.exception.BadRequestException;
import com.readthekjv.model.Verse;
import com.readthekjv.model.dto.CollectionReadResponse;
import com.readthekjv.model.dto.PassageDetailResponse;
import com.readthekjv.model.dto.PassageReadResponse;
import com.readthekjv.model.entity.Passage;
import com.readthekjv.repository.PassageRepository;
import com.readthekjv.repository.UserRepository;
import com.readthekjv.util.NaturalKeyParser;
import com.readthekjv.util.VerseRangeParser;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@Transactional
public class PassageService {

    private final PassageRepository passageRepository;
    private final UserRepository userRepository;
    private final BibleService bibleService;

    public PassageService(PassageRepository passageRepository,
                          UserRepository userRepository,
                          BibleService bibleService) {
        this.passageRepository = passageRepository;
        this.userRepository = userRepository;
        this.bibleService = bibleService;
    }

    /**
     * Catalog for note picker / library: user's passages then globals.
     * Optional q filters against title and derived reference (case-insensitive).
     */
    @Transactional(readOnly = true)
    public List<PassageDetailResponse> listCatalog(Long userId, String q) {
        List<PassageDetailResponse> out = new ArrayList<>();
        for (Passage p : passageRepository.findByUserIdOrderByCreatedAtDesc(userId)) {
            out.add(toDetail(p));
        }
        for (Passage p : passageRepository.findByUserIsNullOrderBySortOrderAsc()) {
            out.add(toDetail(p));
        }
        if (q == null || q.isBlank()) return out;
        String needle = q.trim().toLowerCase(Locale.ROOT);
        return out.stream()
                .filter(d -> matches(d, needle))
                .toList();
    }

    @Transactional(readOnly = true)
    public PassageDetailResponse get(Long userId, UUID id) {
        return toDetail(findReadable(userId, id));
    }

    @Transactional(readOnly = true)
    public PassageReadResponse getHydrated(Long userId, UUID id) {
        Passage p = findReadable(userId, id);
        List<CollectionReadResponse.CollectionVerse> verses = hydrateVerses(p);
        String reference = formatReference(verses);
        String title = blankToNull(p.getTitle());
        return new PassageReadResponse(p.getId(), title, reference, p.getNaturalKey(), verses);
    }

    /** Same budget as collections / public ranges — keeps catalog and hydration bounded. */
    public static final int MAX_PASSAGE_VERSES = 500;

    /**
     * Find-or-create a user passage by natural key. The key is normalized
     * (sort/merge ranges) so equivalent pointers collapse to one row.
     */
    public PassageDetailResponse upsert(Long userId, String naturalKey, String title) {
        try {
            var ranges = VerseRangeParser.rangesFromNaturalKey(naturalKey.trim());
            int verseCount = 0;
            for (VerseRangeParser.Range r : ranges) {
                verseCount += r.to() - r.from() + 1;
                if (verseCount > MAX_PASSAGE_VERSES) {
                    throw new BadRequestException(
                            "Passages are limited to " + MAX_PASSAGE_VERSES + " verses");
                }
            }
            String key = VerseRangeParser.naturalKeyFromRanges(ranges);
            int outerFrom = ranges.stream().mapToInt(VerseRangeParser.Range::from).min().orElseThrow();
            int outerTo = ranges.stream().mapToInt(VerseRangeParser.Range::to).max().orElseThrow();
            return upsertNormalized(userId, key, outerFrom, outerTo, title);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Invalid natural key: " + naturalKey);
        }
    }

    private PassageDetailResponse upsertNormalized(Long userId, String key, int from, int to, String title) {
        String normalizedTitle = blankToNull(title);

        Passage passage = passageRepository.findByUserIdAndNaturalKey(userId, key)
                .orElseGet(() -> {
                    Passage p = new Passage();
                    p.setUser(userRepository.getReferenceById(userId));
                    p.setFromVerseId(from);
                    p.setToVerseId(to);
                    p.setNaturalKey(key);
                    return p;
                });

        if (normalizedTitle != null) {
            passage.setTitle(normalizedTitle);
        } else if (passage.getId() == null) {
            passage.setTitle(null);
        }
        // Canonicalize noncanonical keys (e.g. "2,1" → "1:2") on reuse
        passage.setNaturalKey(key);
        passage.setFromVerseId(from);
        passage.setToVerseId(to);

        return toDetail(passageRepository.save(passage));
    }

    public PassageDetailResponse updateTitle(Long userId, UUID id, String title) {
        Passage p = passageRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Passage not found"));
        p.setTitle(blankToNull(title));
        return toDetail(passageRepository.save(p));
    }

    /** Resolve a passage the user may read: own or global. */
    @Transactional(readOnly = true)
    public Passage findReadable(Long userId, UUID id) {
        return passageRepository.findByIdAndUserId(id, userId)
                .or(() -> passageRepository.findByIdAndUserIsNull(id))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Passage not found"));
    }

    @Transactional(readOnly = true)
    public Passage requireOwnedOrGlobal(Long userId, UUID id) {
        return findReadable(userId, id);
    }

    public List<CollectionReadResponse.CollectionVerse> hydrateVerses(Passage p) {
        List<NaturalKeyParser.Segment> segments;
        try {
            segments = NaturalKeyParser.parse(p.getNaturalKey());
        } catch (Exception e) {
            throw new BadRequestException("Corrupt natural key on passage " + p.getId());
        }
        List<CollectionReadResponse.CollectionVerse> verses = new ArrayList<>();
        for (NaturalKeyParser.Segment seg : segments) {
            for (int vid = seg.from(); vid <= seg.to(); vid++) {
                final int verseId = vid;
                Verse v = bibleService.getVerse(verseId)
                        .orElseThrow(() -> new BadRequestException("Invalid verse id in passage: " + verseId));
                verses.add(CollectionReadResponse.CollectionVerse.from(v));
            }
        }
        return verses;
    }

    public int countVerses(Passage p) {
        try {
            return NaturalKeyParser.parse(p.getNaturalKey()).stream()
                    .mapToInt(s -> s.to() - s.from() + 1)
                    .sum();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Human reference for catalog/list views. Loads only each segment's
     * endpoints so large passages never force full verse materialization.
     */
    public String deriveReference(Passage p) {
        List<NaturalKeyParser.Segment> segments;
        try {
            segments = NaturalKeyParser.parse(p.getNaturalKey());
        } catch (Exception e) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (NaturalKeyParser.Segment seg : segments) {
            Verse from = bibleService.getVerse(seg.from()).orElse(null);
            Verse to = seg.from() == seg.to()
                    ? from
                    : bibleService.getVerse(seg.to()).orElse(null);
            if (from == null || to == null) continue;
            parts.add(formatContiguousEndpoints(from, to));
        }
        return String.join("; ", parts);
    }

    private static String formatContiguousEndpoints(Verse first, Verse last) {
        if (first.id() == last.id()) {
            return first.reference();
        }
        if (first.bookId() == last.bookId() && first.chapter() == last.chapter()) {
            return first.book() + " " + first.chapter() + ":" + first.verse() + "–" + last.verse();
        }
        if (first.bookId() == last.bookId()) {
            return first.book() + " " + first.chapter() + ":" + first.verse()
                    + "–" + last.chapter() + ":" + last.verse();
        }
        return first.reference() + " – " + last.reference();
    }

    public PassageDetailResponse toDetail(Passage p) {
        return PassageDetailResponse.from(p, deriveReference(p));
    }

    /**
     * Human-readable reference for a hydrated verse list. Contiguous id runs
     * collapse to ranges; gaps become separate segments so labels never imply
     * omitted verses (e.g. ids 1,3 → "Genesis 1:1; Genesis 1:3", not "1:1–3").
     */
    public static String formatReference(List<CollectionReadResponse.CollectionVerse> verses) {
        if (verses == null || verses.isEmpty()) return "";
        List<List<CollectionReadResponse.CollectionVerse>> runs = new ArrayList<>();
        List<CollectionReadResponse.CollectionVerse> run = new ArrayList<>();
        run.add(verses.get(0));
        for (int i = 1; i < verses.size(); i++) {
            var prev = verses.get(i - 1);
            var cur = verses.get(i);
            if (cur.id() == prev.id() + 1) {
                run.add(cur);
            } else {
                runs.add(run);
                run = new ArrayList<>();
                run.add(cur);
            }
        }
        runs.add(run);
        return runs.stream()
                .map(PassageService::formatContiguousRun)
                .reduce((a, b) -> a + "; " + b)
                .orElse("");
    }

    private static String formatContiguousRun(List<CollectionReadResponse.CollectionVerse> verses) {
        var first = verses.get(0);
        var last = verses.get(verses.size() - 1);
        if (verses.size() == 1) {
            return first.reference();
        }
        if (first.bookId() == last.bookId() && first.chapter() == last.chapter()) {
            return first.book() + " " + first.chapter() + ":" + first.verse() + "–" + last.verse();
        }
        if (first.bookId() == last.bookId()) {
            return first.book() + " " + first.chapter() + ":" + first.verse()
                    + "–" + last.chapter() + ":" + last.verse();
        }
        return first.reference() + " – " + last.reference();
    }

    private static boolean matches(PassageDetailResponse d, String needle) {
        if (d.reference() != null && d.reference().toLowerCase(Locale.ROOT).contains(needle)) return true;
        if (d.title() != null && d.title().toLowerCase(Locale.ROOT).contains(needle)) return true;
        return d.naturalKey() != null && d.naturalKey().toLowerCase(Locale.ROOT).contains(needle);
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
