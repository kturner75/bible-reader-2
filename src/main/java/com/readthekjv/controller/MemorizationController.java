package com.readthekjv.controller;

import com.readthekjv.model.ChapterInfo;
import com.readthekjv.model.Verse;
import com.readthekjv.model.dto.AddToQueueRequest;
import com.readthekjv.model.dto.MemorizationEntryResponse;
import com.readthekjv.model.dto.PassageContextResponse;
import com.readthekjv.model.dto.PassageContextResponse.ChapterContext;
import com.readthekjv.model.dto.ReviewRequest;
import com.readthekjv.model.dto.VerseSnippet;
import com.readthekjv.model.entity.User;
import com.readthekjv.repository.UserRepository;
import com.readthekjv.service.BibleService;
import com.readthekjv.service.MemorizationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * All endpoints require an active session — "/api/memorization/**" is NOT in SecurityConfig's
 * permitAll list, so unauthenticated requests are rejected before reaching this controller.
 */
@RestController
@RequestMapping("/api/memorization")
public class MemorizationController {

    private final MemorizationService memorizationService;
    private final UserRepository userRepository;
    private final BibleService bibleService;

    public MemorizationController(MemorizationService memorizationService,
                                  UserRepository userRepository,
                                  BibleService bibleService) {
        this.memorizationService = memorizationService;
        this.userRepository = userRepository;
        this.bibleService = bibleService;
    }

    // ─── Helper ───────────────────────────────────────────────────────────────

    private User resolveUser(UserDetails ud) {
        return userRepository.findByEmail(ud.getUsername())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
    }

    // ─── Queue ────────────────────────────────────────────────────────────────

    @GetMapping("/queue")
    public List<MemorizationEntryResponse> getQueue(@AuthenticationPrincipal UserDetails ud) {
        return memorizationService.getQueue(resolveUser(ud).getId());
    }

    @PostMapping("/queue")
    @ResponseStatus(HttpStatus.CREATED)
    public MemorizationEntryResponse addToQueue(@AuthenticationPrincipal UserDetails ud,
                                                @Valid @RequestBody AddToQueueRequest req) {
        return memorizationService.addToQueue(resolveUser(ud).getId(), req.naturalKey());
    }

    @PatchMapping("/queue/{entryId}")
    public MemorizationEntryResponse updatePassage(@AuthenticationPrincipal UserDetails ud,
                                                   @PathVariable UUID entryId,
                                                   @Valid @RequestBody AddToQueueRequest req) {
        return memorizationService.updatePassage(resolveUser(ud).getId(), entryId, req.naturalKey());
    }

    @DeleteMapping("/queue/{entryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeFromQueue(@AuthenticationPrincipal UserDetails ud,
                                @PathVariable UUID entryId) {
        memorizationService.removeFromQueue(resolveUser(ud).getId(), entryId);
    }

    // ─── Training ─────────────────────────────────────────────────────────────

    @PostMapping("/queue/{entryId}/review")
    public MemorizationEntryResponse submitReview(@AuthenticationPrincipal UserDetails ud,
                                                  @PathVariable UUID entryId,
                                                  @Valid @RequestBody ReviewRequest req) {
        return memorizationService.submitReview(resolveUser(ud).getId(), entryId, req.quality());
    }

    // ─── Passage Context ──────────────────────────────────────────────────────

    /**
     * Returns prev/current/next chapter verses for the passage picker modal.
     * Chapter boundaries stay within the same book.
     */
    @GetMapping("/context/{verseId}")
    public PassageContextResponse getPassageContext(@PathVariable int verseId) {
        Verse verse = bibleService.getVerse(verseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Verse not found"));

        List<ChapterInfo> chapters = bibleService.getChapters(verse.bookId());
        int currentChapterNum = verse.chapter();

        int currentIdx = -1;
        for (int i = 0; i < chapters.size(); i++) {
            if (chapters.get(i).chapter() == currentChapterNum) {
                currentIdx = i;
                break;
            }
        }
        if (currentIdx < 0) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Chapter not found");
        }

        ChapterContext prevChapter = currentIdx > 0
                ? buildChapterContext(verse.book(), verse.bookId(), chapters.get(currentIdx - 1))
                : null;
        ChapterContext currentChapter =
                buildChapterContext(verse.book(), verse.bookId(), chapters.get(currentIdx));
        ChapterContext nextChapter = currentIdx < chapters.size() - 1
                ? buildChapterContext(verse.book(), verse.bookId(), chapters.get(currentIdx + 1))
                : null;

        return new PassageContextResponse(prevChapter, currentChapter, nextChapter);
    }

    private ChapterContext buildChapterContext(String bookName, int bookId, ChapterInfo ci) {
        List<VerseSnippet> verses = bibleService.getVerses(ci.firstVerseId(), ci.verseCount())
                .stream()
                .map(v -> new VerseSnippet(v.id(), v.verse(), v.reference(), v.text()))
                .toList();
        return new ChapterContext(bookId, bookName, ci.chapter(), verses);
    }
}
