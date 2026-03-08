package com.readthekjv.service;

import com.readthekjv.model.Verse;
import com.readthekjv.model.dto.MemorizationEntryResponse;
import com.readthekjv.model.dto.PassageResponse;
import com.readthekjv.model.entity.MemorizationEntry;
import com.readthekjv.model.entity.Passage;
import com.readthekjv.repository.MemorizationEntryRepository;
import com.readthekjv.repository.PassageRepository;
import com.readthekjv.repository.UserRepository;
import jakarta.transaction.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
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

    // ─── Natural Key ──────────────────────────────────────────────────────────

    private static String buildNaturalKey(int fromVerseId, int toVerseId) {
        return fromVerseId == toVerseId
                ? String.valueOf(fromVerseId)
                : fromVerseId + ":" + toVerseId;
    }

    // ─── Response Enrichment ──────────────────────────────────────────────────

    private MemorizationEntryResponse toResponse(MemorizationEntry entry) {
        Verse fromVerse = bibleService.getVerse(entry.getPassage().getFromVerseId()).orElse(null);
        String ref  = fromVerse != null ? fromVerse.reference() : entry.getPassage().getNaturalKey();
        String text = fromVerse != null ? fromVerse.text() : "";
        return new MemorizationEntryResponse(
                entry.getId(),
                PassageResponse.from(entry.getPassage()),
                ref,
                text,
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
     * Idempotent — returns the existing entry if this range is already queued.
     */
    public MemorizationEntryResponse addToQueue(Long userId, int fromVerseId, int toVerseId) {
        if (toVerseId < fromVerseId) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "toVerseId must be >= fromVerseId");
        }

        String key = buildNaturalKey(fromVerseId, toVerseId);

        Passage passage = passageRepo.findByUserIdAndNaturalKey(userId, key)
                .orElseGet(() -> {
                    Passage p = new Passage();
                    p.setUser(userRepo.getReferenceById(userId));
                    p.setFromVerseId(fromVerseId);
                    p.setToVerseId(toVerseId);
                    p.setNaturalKey(key);
                    return passageRepo.save(p);
                });

        return entryRepo.findByUserIdAndPassageId(userId, passage.getId())
                .map(this::toResponse)
                .orElseGet(() -> {
                    MemorizationEntry entry = new MemorizationEntry();
                    entry.setUser(userRepo.getReferenceById(userId));
                    entry.setPassage(passage);
                    return toResponse(entryRepo.save(entry));
                });
    }

    /**
     * Idempotent — no-op if the entry doesn't exist or belongs to a different user.
     * Only the entry is deleted; the passage row is left intact for potential reuse.
     */
    public void removeFromQueue(Long userId, UUID entryId) {
        entryRepo.findById(entryId)
                .filter(e -> e.getUser().getId().equals(userId))
                .ifPresent(entryRepo::delete);
    }

    // ─── Training / SM-2 ──────────────────────────────────────────────────────

    /**
     * Applies the SM-2 spaced-repetition algorithm and persists the result.
     *
     * quality: 0 = Again, 3 = Hard, 4 = Good, 5 = Easy
     */
    public MemorizationEntryResponse submitReview(Long userId, UUID entryId, int quality) {
        MemorizationEntry entry = entryRepo.findById(entryId)
                .filter(e -> e.getUser().getId().equals(userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Entry not found"));

        // ── Interval & mastery update ─────────────────────────────────────────
        if (quality < 3) {
            // Incorrect — reset
            entry.setIntervalDays(1);
            entry.setMasteryLevel((short) Math.max(0, entry.getMasteryLevel() - 1));
        } else {
            // Correct — advance
            int newInterval = switch (entry.getMasteryLevel()) {
                case 0  -> 1;
                case 1  -> 6;
                default -> (int) Math.round(entry.getIntervalDays() * entry.getEaseFactor().doubleValue());
            };
            entry.setIntervalDays(Math.max(1, newInterval));
            entry.setMasteryLevel((short) Math.min(5, entry.getMasteryLevel() + 1));
        }

        // ── Ease factor update (SM-2 formula) ─────────────────────────────────
        double delta = 0.1 - (5 - quality) * (0.08 + (5 - quality) * 0.02);
        double newEase = entry.getEaseFactor().doubleValue() + delta;
        entry.setEaseFactor(BigDecimal.valueOf(Math.max(1.30, newEase))
                .setScale(2, RoundingMode.HALF_UP));

        // ── Schedule next review ───────────────────────────────────────────────
        entry.setNextReviewAt(LocalDate.now().plusDays(entry.getIntervalDays()));

        return toResponse(entryRepo.save(entry));
    }
}
