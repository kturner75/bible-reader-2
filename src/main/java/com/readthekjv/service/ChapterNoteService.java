package com.readthekjv.service;

import com.readthekjv.exception.BadRequestException;
import com.readthekjv.model.Book;
import com.readthekjv.model.ChapterInfo;
import com.readthekjv.model.dto.ChapterNoteResponse;
import com.readthekjv.model.entity.ChapterNote;
import com.readthekjv.repository.ChapterNoteRepository;
import com.readthekjv.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class ChapterNoteService {

    private final ChapterNoteRepository chapterNoteRepository;
    private final UserRepository userRepository;
    private final BibleService bibleService;

    public ChapterNoteService(ChapterNoteRepository chapterNoteRepository,
                              UserRepository userRepository,
                              BibleService bibleService) {
        this.chapterNoteRepository = chapterNoteRepository;
        this.userRepository = userRepository;
        this.bibleService = bibleService;
    }

    @Transactional(readOnly = true)
    public List<ChapterNoteResponse> getNotes(Long userId) {
        return chapterNoteRepository.findByUserIdOrderByBookIdAscChapterAsc(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    public ChapterNoteResponse upsertNote(Long userId, int bookId, int chapter, String note) {
        validateChapter(bookId, chapter);
        ChapterNote entity = chapterNoteRepository
                .findByUserIdAndBookIdAndChapter(userId, bookId, chapter)
                .orElseGet(() -> {
                    ChapterNote n = new ChapterNote();
                    n.setUser(userRepository.getReferenceById(userId));
                    n.setBookId(bookId);
                    n.setChapter(chapter);
                    return n;
                });
        entity.setNote(note.trim());
        return toResponse(chapterNoteRepository.save(entity));
    }

    public void deleteNote(Long userId, int bookId, int chapter) {
        chapterNoteRepository.findByUserIdAndBookIdAndChapter(userId, bookId, chapter)
                .ifPresent(chapterNoteRepository::delete);
    }

    private void validateChapter(int bookId, int chapter) {
        Book book = bibleService.getBook(bookId)
                .orElseThrow(() -> new BadRequestException("Invalid book id: " + bookId));
        if (chapter < 1 || chapter > book.chapters()) {
            throw new BadRequestException("Invalid chapter " + chapter + " for " + book.name());
        }
    }

    private ChapterNoteResponse toResponse(ChapterNote n) {
        Book book = bibleService.getBook(n.getBookId()).orElseThrow();
        ChapterInfo info = bibleService.getChapters(n.getBookId()).stream()
                .filter(ci -> ci.chapter() == n.getChapter())
                .findFirst()
                .orElseThrow();
        return ChapterNoteResponse.from(n, book.name(), info.firstVerseId(), info.verseCount());
    }
}
