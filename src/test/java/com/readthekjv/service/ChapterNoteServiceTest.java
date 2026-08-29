package com.readthekjv.service;

import com.readthekjv.exception.BadRequestException;
import com.readthekjv.model.Verse;
import com.readthekjv.model.dto.ChapterNoteResponse;
import com.readthekjv.model.entity.ChapterNote;
import com.readthekjv.model.entity.User;
import com.readthekjv.repository.ChapterNoteRepository;
import com.readthekjv.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChapterNoteServiceTest {

    private static final Long USER_ID = 42L;

    private ChapterNoteRepository chapterNoteRepository;
    private UserRepository userRepository;
    private ChapterNoteService service;

    @BeforeEach
    void setUp() {
        chapterNoteRepository = mock(ChapterNoteRepository.class);
        userRepository = mock(UserRepository.class);

        // Real BibleService with a tiny Genesis: ch1 = 2 verses, ch2 = 3 verses, ch3 = 2 verses
        BibleService bibleService = new BibleService();
        bibleService.loadVerses(List.of(
            new Verse(1, "Genesis", 1, 1, 1, "In the beginning..."),
            new Verse(2, "Genesis", 1, 1, 2, "And the earth..."),
            new Verse(3, "Genesis", 1, 2, 1, "Thus the heavens..."),
            new Verse(4, "Genesis", 1, 2, 2, "And on the seventh day..."),
            new Verse(5, "Genesis", 1, 2, 3, "And God blessed..."),
            new Verse(6, "Genesis", 1, 3, 1, "Now the serpent..."),
            new Verse(7, "Genesis", 1, 3, 2, "And the woman said...")
        ));

        service = new ChapterNoteService(chapterNoteRepository, userRepository, bibleService);

        when(userRepository.getReferenceById(USER_ID)).thenReturn(new User());
        when(chapterNoteRepository.save(any(ChapterNote.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private ChapterNote existingNote(int bookId, int chapter, String note) {
        ChapterNote n = new ChapterNote();
        n.setBookId(bookId);
        n.setChapter(chapter);
        n.setNote(note);
        return n;
    }

    @Test
    void upsertCreatesNewNoteWithTrimmedText() {
        when(chapterNoteRepository.findByUserIdAndBookIdAndChapter(USER_ID, 1, 3))
            .thenReturn(Optional.empty());

        ChapterNoteResponse res = service.upsertNote(USER_ID, 1, 3, "  My study notes  ");

        ArgumentCaptor<ChapterNote> captor = ArgumentCaptor.forClass(ChapterNote.class);
        verify(chapterNoteRepository).save(captor.capture());
        assertEquals("My study notes", captor.getValue().getNote());
        assertEquals(1, captor.getValue().getBookId());
        assertEquals(3, captor.getValue().getChapter());
        assertEquals("My study notes", res.note());
    }

    @Test
    void upsertUpdatesExistingNoteWithoutCreatingNewEntity() {
        ChapterNote existing = existingNote(1, 3, "old text");
        when(chapterNoteRepository.findByUserIdAndBookIdAndChapter(USER_ID, 1, 3))
            .thenReturn(Optional.of(existing));

        service.upsertNote(USER_ID, 1, 3, "new text");

        verify(chapterNoteRepository).save(same(existing));
        assertEquals("new text", existing.getNote());
        verify(userRepository, never()).getReferenceById(any());
    }

    @Test
    void upsertRefusesPastedEmbedOverTwelveAndDoesNotSave() {
        when(chapterNoteRepository.findByUserIdAndBookIdAndChapter(USER_ID, 1, 3))
            .thenReturn(Optional.empty());

        BadRequestException ex = assertThrows(BadRequestException.class,
            () -> service.upsertNote(USER_ID, 1, 3, "See [e=1-13]"));
        assertTrue(ex.getMessage().contains("12"));
        assertTrue(ex.getMessage().contains("13"));
        verify(chapterNoteRepository, never()).save(any());
    }

    @Test
    void upsertRejectsInvalidBookId() {
        assertThrows(BadRequestException.class,
            () -> service.upsertNote(USER_ID, 99, 1, "note"));
        verify(chapterNoteRepository, never()).save(any());
    }

    @Test
    void upsertRejectsChapterOutOfRange() {
        assertThrows(BadRequestException.class,
            () -> service.upsertNote(USER_ID, 1, 0, "note"));
        assertThrows(BadRequestException.class,
            () -> service.upsertNote(USER_ID, 1, 4, "note"));
        verify(chapterNoteRepository, never()).save(any());
    }

    @Test
    void deleteRemovesExistingNote() {
        ChapterNote existing = existingNote(1, 3, "text");
        when(chapterNoteRepository.findByUserIdAndBookIdAndChapter(USER_ID, 1, 3))
            .thenReturn(Optional.of(existing));

        service.deleteNote(USER_ID, 1, 3);

        verify(chapterNoteRepository).delete(existing);
    }

    @Test
    void deleteIsIdempotentWhenNoteAbsent() {
        when(chapterNoteRepository.findByUserIdAndBookIdAndChapter(USER_ID, 1, 3))
            .thenReturn(Optional.empty());

        assertDoesNotThrow(() -> service.deleteNote(USER_ID, 1, 3));
        verify(chapterNoteRepository, never()).delete(any());
    }

    @Test
    void getNotesEnrichesWithBookNameAndChapterInfo() {
        when(chapterNoteRepository.findByUserIdOrderByBookIdAscChapterAsc(USER_ID))
            .thenReturn(List.of(existingNote(1, 3, "note text")));

        List<ChapterNoteResponse> notes = service.getNotes(USER_ID);

        assertEquals(1, notes.size());
        ChapterNoteResponse res = notes.get(0);
        assertEquals("Genesis", res.bookName());
        assertEquals(3, res.chapter());
        assertEquals(6, res.firstVerseId());
        assertEquals(2, res.verseCount());
        assertEquals("note text", res.note());
    }
}
