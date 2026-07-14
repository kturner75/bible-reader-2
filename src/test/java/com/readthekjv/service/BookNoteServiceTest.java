package com.readthekjv.service;

import com.readthekjv.exception.BadRequestException;
import com.readthekjv.model.Verse;
import com.readthekjv.model.dto.BookNoteResponse;
import com.readthekjv.model.entity.BookNote;
import com.readthekjv.model.entity.User;
import com.readthekjv.repository.BookNoteRepository;
import com.readthekjv.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BookNoteServiceTest {

    private static final Long USER_ID = 42L;

    private BookNoteRepository bookNoteRepository;
    private UserRepository userRepository;
    private BookNoteService service;

    @BeforeEach
    void setUp() {
        bookNoteRepository = mock(BookNoteRepository.class);
        userRepository = mock(UserRepository.class);

        // Real BibleService (concrete class unmockable on this JDK) with a tiny Genesis
        BibleService bibleService = new BibleService();
        bibleService.loadVerses(List.of(
            new Verse(1, "Genesis", 1, 1, 1, "In the beginning..."),
            new Verse(2, "Genesis", 1, 1, 2, "And the earth..."),
            new Verse(3, "Genesis", 1, 2, 1, "Thus the heavens...")
        ));

        service = new BookNoteService(bookNoteRepository, userRepository, bibleService);

        when(userRepository.getReferenceById(USER_ID)).thenReturn(new User());
        when(bookNoteRepository.save(any(BookNote.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private BookNote existingNote(int bookId, String note) {
        BookNote n = new BookNote();
        n.setBookId(bookId);
        n.setNote(note);
        return n;
    }

    @Test
    void upsertCreatesNewNoteWithTrimmedText() {
        when(bookNoteRepository.findByUserIdAndBookId(USER_ID, 1))
            .thenReturn(Optional.empty());

        BookNoteResponse res = service.upsertNote(USER_ID, 1, "  Book outline  ");

        ArgumentCaptor<BookNote> captor = ArgumentCaptor.forClass(BookNote.class);
        verify(bookNoteRepository).save(captor.capture());
        assertEquals("Book outline", captor.getValue().getNote());
        assertEquals(1, captor.getValue().getBookId());
        assertEquals("Book outline", res.note());
    }

    @Test
    void upsertUpdatesExistingNoteWithoutCreatingNewEntity() {
        BookNote existing = existingNote(1, "old text");
        when(bookNoteRepository.findByUserIdAndBookId(USER_ID, 1))
            .thenReturn(Optional.of(existing));

        service.upsertNote(USER_ID, 1, "new text");

        verify(bookNoteRepository).save(same(existing));
        assertEquals("new text", existing.getNote());
        verify(userRepository, never()).getReferenceById(any());
    }

    @Test
    void upsertRejectsInvalidBookId() {
        assertThrows(BadRequestException.class,
            () -> service.upsertNote(USER_ID, 99, "note"));
        verify(bookNoteRepository, never()).save(any());
    }

    @Test
    void deleteRemovesExistingNote() {
        BookNote existing = existingNote(1, "text");
        when(bookNoteRepository.findByUserIdAndBookId(USER_ID, 1))
            .thenReturn(Optional.of(existing));

        service.deleteNote(USER_ID, 1);

        verify(bookNoteRepository).delete(existing);
    }

    @Test
    void deleteIsIdempotentWhenNoteAbsent() {
        when(bookNoteRepository.findByUserIdAndBookId(USER_ID, 1))
            .thenReturn(Optional.empty());

        assertDoesNotThrow(() -> service.deleteNote(USER_ID, 1));
        verify(bookNoteRepository, never()).delete(any());
    }

    @Test
    void getNotesEnrichesWithBookNameAndFirstVerseId() {
        when(bookNoteRepository.findByUserIdOrderByBookIdAsc(USER_ID))
            .thenReturn(List.of(existingNote(1, "note text")));

        List<BookNoteResponse> notes = service.getNotes(USER_ID);

        assertEquals(1, notes.size());
        BookNoteResponse res = notes.get(0);
        assertEquals("Genesis", res.bookName());
        assertEquals(1, res.firstVerseId());
        assertEquals("note text", res.note());
    }
}
