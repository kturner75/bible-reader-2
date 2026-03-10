package com.readthekjv.controller;

import com.readthekjv.model.ChapterInfo;
import com.readthekjv.model.Verse;
import com.readthekjv.model.dto.AddToQueueRequest;
import com.readthekjv.model.dto.GlobalPassageResponse;
import com.readthekjv.model.dto.MemorizationEntryResponse;
import com.readthekjv.model.dto.PassageContextResponse;
import com.readthekjv.model.dto.ReciteResponse;
import com.readthekjv.model.dto.StreakResponse;
import com.readthekjv.model.dto.PassageContextResponse.ChapterContext;
import com.readthekjv.model.dto.ReviewRequest;
import com.readthekjv.model.dto.VerseSnippet;
import com.readthekjv.model.entity.User;
import com.readthekjv.repository.UserRepository;
import com.readthekjv.service.BibleService;
import com.readthekjv.service.MemorizationService;
import com.readthekjv.service.WhisperService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * All endpoints require an active session — "/api/memorization/**" is NOT in SecurityConfig's
 * permitAll list, so unauthenticated requests are rejected before reaching this controller.
 */
@RestController
@RequestMapping("/api/memorization")
public class MemorizationController {

    private static final Logger log = LoggerFactory.getLogger(MemorizationController.class);
    private static final long MAX_AUDIO_BYTES = 25L * 1024 * 1024; // 25 MB (Whisper API limit)

    private final MemorizationService memorizationService;
    private final UserRepository userRepository;
    private final BibleService bibleService;
    private final WhisperService whisperService;

    public MemorizationController(MemorizationService memorizationService,
                                  UserRepository userRepository,
                                  BibleService bibleService,
                                  WhisperService whisperService) {
        this.memorizationService = memorizationService;
        this.userRepository = userRepository;
        this.bibleService = bibleService;
        this.whisperService = whisperService;
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

    // ─── Streak ───────────────────────────────────────────────────────────────

    @GetMapping("/streak")
    public StreakResponse getStreak(@AuthenticationPrincipal UserDetails ud) {
        return memorizationService.getStreak(resolveUser(ud).getId());
    }

    // ─── Global Passages ──────────────────────────────────────────────────────

    @GetMapping("/global-passages")
    public List<GlobalPassageResponse> getGlobalPassages(@AuthenticationPrincipal UserDetails ud) {
        return memorizationService.getGlobalPassages(resolveUser(ud).getId());
    }

    // ─── Training ─────────────────────────────────────────────────────────────

    @PostMapping("/queue/{entryId}/review")
    public MemorizationEntryResponse submitReview(@AuthenticationPrincipal UserDetails ud,
                                                  @PathVariable UUID entryId,
                                                  @Valid @RequestBody ReviewRequest req) {
        return memorizationService.submitReview(resolveUser(ud).getId(), entryId, req.quality());
    }

    /**
     * Transcribes a voice recitation of a memorized passage via OpenAI Whisper.
     * Returns the transcript and the expected passage text so the frontend can
     * perform a word-level diff and display an accuracy score.
     *
     * The SM-2 review is still submitted separately via POST /queue/{entryId}/review
     * after the user selects a quality rating.
     */
    @PostMapping(value = "/queue/{entryId}/recite", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ReciteResponse> submitRecitation(
            @AuthenticationPrincipal UserDetails ud,
            @PathVariable UUID entryId,
            @RequestParam("audio") MultipartFile audio) {

        if (!whisperService.isEnabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }

        if (audio.getSize() > MAX_AUDIO_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Audio file too large (max 25 MB)");
        }

        Long userId = resolveUser(ud).getId();

        MemorizationEntryResponse entry = memorizationService.getQueue(userId)
                .stream()
                .filter(e -> e.id().equals(entryId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Entry not found"));

        String expectedText = entry.verses().stream()
                .map(VerseSnippet::text)
                .collect(Collectors.joining(" "));

        try {
            byte[] audioBytes = audio.getBytes();
            String transcript = whisperService.transcribe(audioBytes, audio.getContentType(), expectedText);
            return ResponseEntity.ok(new ReciteResponse(transcript, expectedText));
        } catch (Exception e) {
            log.error("Whisper transcription failed for entry {}", entryId, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Transcription failed: " + e.getMessage());
        }
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
