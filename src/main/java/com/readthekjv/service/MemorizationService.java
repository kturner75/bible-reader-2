package com.readthekjv.service;

import com.readthekjv.model.dto.MemorizationEntryResponse;
import com.readthekjv.model.dto.PassageResponse;
import com.readthekjv.model.dto.VerseSnippet;
import com.readthekjv.model.entity.MemorizationEntry;
import com.readthekjv.model.entity.Passage;
import com.readthekjv.repository.MemorizationEntryRepository;
import com.readthekjv.repository.PassageRepository;
import com.readthekjv.repository.UserRepository;
import com.readthekjv.util.NaturalKeyParser;
import jakarta.transaction.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class MemorizationService {

    private final MemorizationEntryRepository entryRepo;
    private final PassageRepository passageRepo;
    private final UserRepository userRepo;
    private final BibleService bibleService;

    public MemorizationService(MemorizationEntryRepository entryRepo,
                               PassageRepository passageRepo,
                               UserRepository userRepo,
                               BibleService bibleService) {
        this.entryRepo = entryRepo;
        this.passageRepo = passageRepo;
        this.userRepo = userRepo;
        this.bibleService = bibleService;
    }

    // ─── Response Enrichment ──────────────────────────────────────────────────

    private MemorizationEntryResponse toResponse(MemorizationEntry entry) {
        String naturalKey = entry.getPassage().getNaturalKey();
        List<NaturalKeyParser.Segment> segments;
        try {
            segments = NaturalKeyParser.parse(naturalKey);
        } catch (Exception e) {
            segments = List.of();
        }

        // Collect all verses across all segments in order
        List<VerseSnippet> verses = new ArrayList<>();
        for (NaturalKeyParser.Segment seg : segments) {
            for (int id = seg.from(); id <= seg.to(); id++) {
                bibleService.getVerse(id).ifPresent(v ->
                    verses.add(new VerseSnippet(v.id(), v.verse(), v.reference(), v.text()))
                );
            }
        }

        String fromRef = verses.isEmpty() ? naturalKey : verses.get(0).reference();
        String toRef   = verses.isEmpty() ? naturalKey : verses.get(verses.size() - 1).reference();

        return new MemorizationEntryResponse(
                entry.getId(),
                PassageResponse.from(entry.getPassage()),
                fromRef,
                toRef,
                verses,
                entry.getMasteryLevel(),
                entry.getNextReviewAt(),
                entry.getAddedAt()
        );
    }

    // ─── Queue Operations ─────────────────────────────────────────────────────

    public List<MemorizationEntryResponse> getQueue(Long userId) {
        return entryRepo.findByUserIdWithPassage(userId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Idempotent — returns the existing entry if this natural key is already queued.
     * Validates and parses the natural key to derive outer bounds.
     */
    public MemorizationEntryResponse addToQueue(Long userId, String naturalKey) {
        if (!NaturalKeyParser.isValid(naturalKey)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid natural key: " + naturalKey);
        }

        List<NaturalKeyParser.Segment> segments = NaturalKeyParser.parse(naturalKey);
        int fromVerseId = NaturalKeyParser.outerFrom(segments);
        int toVerseId   = NaturalKeyParser.outerTo(segments);

        Passage passage = passageRepo.findByUserIdAndNaturalKey(userId, naturalKey)
                .orElseGet(() -> {
                    Passage p = new Passage();
                    p.setUser(userRepo.getReferenceById(userId));
                    p.setFromVerseId(fromVerseId);
                    p.setToVerseId(toVerseId);
                    p.setNaturalKey(naturalKey);
                    return passageRepo.save(p);
                });

        return entryRepo.findByUserIdAndPassageId(userId, passage.getId())
                .map(this::toResponse)
                .orElseGet(() -> {
                    MemorizationEntry e = new MemorizationEntry();
                    e.setUser(userRepo.getReferenceById(userId));
                    e.setPassage(passage);
                    return toResponse(entryRepo.save(e));
                });
    }

    /**
     * Updates an existing entry's passage to a new natural key (edit selection).
     * Resets mastery to 0 since the passage content has changed.
     */
    public MemorizationEntryResponse updatePassage(Long userId, UUID entryId, String naturalKey) {
        if (!NaturalKeyParser.isValid(naturalKey)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid natural key: " + naturalKey);
        }

        MemorizationEntry entry = entryRepo.findById(entryId)
                .filter(e -> e.getUser().getId().equals(userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Entry not found"));

        List<NaturalKeyParser.Segment> segments = NaturalKeyParser.parse(naturalKey);
        int fromVerseId = NaturalKeyParser.outerFrom(segments);
        int toVerseId   = NaturalKeyParser.outerTo(segments);

        Passage passage = passageRepo.findByUserIdAndNaturalKey(userId, naturalKey)
                .orElseGet(() -> {
                    Passage p = new Passage();
                    p.setUser(userRepo.getReferenceById(userId));
                    p.setFromVerseId(fromVerseId);
                    p.setToVerseId(toVerseId);
                    p.setNaturalKey(naturalKey);
                    return passageRepo.save(p);
                });

        entry.setPassage(passage);
        entry.setMasteryLevel((short) 0);
        entry.setIntervalDays(1);
        entry.setEaseFactor(new BigDecimal("2.50"));
        entry.setNextReviewAt(null);

        return toResponse(entryRepo.save(entry));
    }

    /**
     * Idempotent — no-op if the entry doesn't exist or belongs to a different user.
     */
    public void removeFromQueue(Long userId, UUID entryId) {
        entryRepo.findById(entryId)
                .filter(e -> e.getUser().getId().equals(userId))
                .ifPresent(entryRepo::delete);
    }

    // ─── Training / SM-2 ──────────────────────────────────────────────────────

    /**
     * Applies the SM-2 spaced-repetition algorithm and persists the result.
     * quality: 0 = Again, 3 = Hard, 4 = Good, 5 = Easy
     */
    public MemorizationEntryResponse submitReview(Long userId, UUID entryId, int quality) {
        MemorizationEntry entry = entryRepo.findById(entryId)
                .filter(e -> e.getUser().getId().equals(userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Entry not found"));

        if (quality < 3) {
            entry.setIntervalDays(1);
            entry.setMasteryLevel((short) Math.max(0, entry.getMasteryLevel() - 1));
        } else {
            int newInterval = switch (entry.getMasteryLevel()) {
                case 0  -> 1;
                case 1  -> 6;
                default -> (int) Math.round(entry.getIntervalDays() * entry.getEaseFactor().doubleValue());
            };
            entry.setIntervalDays(Math.max(1, newInterval));
            entry.setMasteryLevel((short) Math.min(5, entry.getMasteryLevel() + 1));
        }

        double delta = 0.1 - (5 - quality) * (0.08 + (5 - quality) * 0.02);
        double newEase = entry.getEaseFactor().doubleValue() + delta;
        entry.setEaseFactor(BigDecimal.valueOf(Math.max(1.30, newEase))
                .setScale(2, RoundingMode.HALF_UP));

        entry.setNextReviewAt(LocalDate.now().plusDays(entry.getIntervalDays()));

        return toResponse(entryRepo.save(entry));
    }
}
