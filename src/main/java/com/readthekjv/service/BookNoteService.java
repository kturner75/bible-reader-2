package com.readthekjv.service;

import com.readthekjv.exception.BadRequestException;
import com.readthekjv.model.Book;
import com.readthekjv.model.dto.BookNoteResponse;
import com.readthekjv.model.entity.BookNote;
import com.readthekjv.repository.BookNoteRepository;
import com.readthekjv.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class BookNoteService {

    private final BookNoteRepository bookNoteRepository;
    private final UserRepository userRepository;
    private final BibleService bibleService;

    public BookNoteService(BookNoteRepository bookNoteRepository,
                           UserRepository userRepository,
                           BibleService bibleService) {
        this.bookNoteRepository = bookNoteRepository;
        this.userRepository = userRepository;
        this.bibleService = bibleService;
    }

    @Transactional(readOnly = true)
    public List<BookNoteResponse> getNotes(Long userId) {
        return bookNoteRepository.findByUserIdOrderByBookIdAsc(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    public BookNoteResponse upsertNote(Long userId, int bookId, String note) {
        validateBook(bookId);
        BookNote entity = bookNoteRepository
                .findByUserIdAndBookId(userId, bookId)
                .orElseGet(() -> {
                    BookNote n = new BookNote();
                    n.setUser(userRepository.getReferenceById(userId));
                    n.setBookId(bookId);
                    return n;
                });
        entity.setNote(note.trim());
        return toResponse(bookNoteRepository.save(entity));
    }

    public void deleteNote(Long userId, int bookId) {
        bookNoteRepository.findByUserIdAndBookId(userId, bookId)
                .ifPresent(bookNoteRepository::delete);
    }

    private void validateBook(int bookId) {
        if (bibleService.getBook(bookId).isEmpty()) {
            throw new BadRequestException("Invalid book id: " + bookId);
        }
    }

    private BookNoteResponse toResponse(BookNote n) {
        Book book = bibleService.getBook(n.getBookId()).orElseThrow();
        return BookNoteResponse.from(n, book.name(), book.firstVerseId());
    }
}
