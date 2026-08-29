package com.readthekjv.service;

import com.readthekjv.exception.BadRequestException;
import com.readthekjv.model.dto.SermonNoteResponse;
import com.readthekjv.model.dto.SermonNoteSummary;
import com.readthekjv.model.entity.SermonNote;
import com.readthekjv.model.entity.User;
import com.readthekjv.repository.SermonNoteRepository;
import com.readthekjv.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SermonNoteServiceTest {

    private static final Long USER_ID = 42L;
    private static final UUID NOTE_ID = UUID.randomUUID();

    private SermonNoteRepository sermonNoteRepository;
    private UserRepository userRepository;
    private SermonNoteService service;

    @BeforeEach
    void setUp() {
        sermonNoteRepository = mock(SermonNoteRepository.class);
        userRepository = mock(UserRepository.class);

        service = new SermonNoteService(sermonNoteRepository, userRepository);

        when(userRepository.getReferenceById(USER_ID)).thenReturn(new User());
        when(sermonNoteRepository.save(any(SermonNote.class))).thenAnswer(inv -> {
            SermonNote n = inv.getArgument(0);
            if (n.getId() == null) {
                // DB assigns the id on insert; simulate it for DTO mapping
                ReflectionTestUtils.setField(n, "id", UUID.randomUUID());
            }
            return n;
        });
    }

    private SermonNote existingNote(String title, String note) {
        SermonNote n = new SermonNote();
        ReflectionTestUtils.setField(n, "id", NOTE_ID);
        n.setTitle(title);
        n.setNote(note);
        return n;
    }

    @Test
    void createTrimsTitleAndNote() {
        SermonNoteResponse res = service.create(USER_ID, "  Sermon on the Mount  ", "  Body text  ");

        ArgumentCaptor<SermonNote> captor = ArgumentCaptor.forClass(SermonNote.class);
        verify(sermonNoteRepository).save(captor.capture());
        assertEquals("Sermon on the Mount", captor.getValue().getTitle());
        assertEquals("Body text", captor.getValue().getNote());
        assertEquals("Sermon on the Mount", res.title());
        assertEquals("Body text", res.note());
    }

    @Test
    void createRefusesPastedEmbedOverTwelveAndDoesNotSave() {
        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> service.create(USER_ID, "Title", "See [e=1-13]"));
        assertTrue(ex.getMessage().contains("12"));
        assertTrue(ex.getMessage().contains("13"));
        verify(sermonNoteRepository, never()).save(any());
    }

    @Test
    void updateRefusesPastedEmbedOverTwelveAndDoesNotSave() {
        SermonNote existing = existingNote("Old", "old text");
        when(sermonNoteRepository.findByIdAndUserId(NOTE_ID, USER_ID)).thenReturn(Optional.of(existing));

        assertThrows(BadRequestException.class,
                () -> service.update(USER_ID, NOTE_ID, "New Title", "[e=1-10,20-22]"));
        verify(sermonNoteRepository, never()).save(any());
        assertEquals("old text", existing.getNote());
    }

    @Test
    void createAcceptsTwelveVerseEmbed() {
        SermonNoteResponse res = service.create(USER_ID, "Title", "[e=1-12]");
        assertEquals("[e=1-12]", res.note());
        verify(sermonNoteRepository).save(any(SermonNote.class));
    }

    @Test
    void updateReplacesTitleAndNoteOnExistingEntity() {
        SermonNote existing = existingNote("Old", "old text");
        when(sermonNoteRepository.findByIdAndUserId(NOTE_ID, USER_ID)).thenReturn(Optional.of(existing));

        service.update(USER_ID, NOTE_ID, "New Title", "new text");

        verify(sermonNoteRepository).save(existing);
        assertEquals("New Title", existing.getTitle());
        assertEquals("new text", existing.getNote());
    }

    @Test
    void notOwnedNoteIs404() {
        when(sermonNoteRepository.findByIdAndUserId(NOTE_ID, USER_ID)).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class, () -> service.get(USER_ID, NOTE_ID));
        assertThrows(ResponseStatusException.class, () -> service.update(USER_ID, NOTE_ID, "X", "Y"));
        assertThrows(ResponseStatusException.class, () -> service.delete(USER_ID, NOTE_ID));
    }

    @Test
    void deleteRemovesOwnedNote() {
        SermonNote existing = existingNote("Gone", "text");
        when(sermonNoteRepository.findByIdAndUserId(NOTE_ID, USER_ID)).thenReturn(Optional.of(existing));

        service.delete(USER_ID, NOTE_ID);

        verify(sermonNoteRepository).delete(existing);
    }

    @Test
    void listMapsToSummariesWithTruncatedSnippet() {
        String longNote = "word ".repeat(50).trim();
        when(sermonNoteRepository.findByUserIdOrderByUpdatedAtDesc(USER_ID))
            .thenReturn(List.of(existingNote("Long Note", longNote)));

        List<SermonNoteSummary> summaries = service.list(USER_ID);

        assertEquals(1, summaries.size());
        SermonNoteSummary res = summaries.get(0);
        assertEquals("Long Note", res.title());
        assertTrue(res.snippet().length() <= 161);
        assertTrue(res.snippet().endsWith("…"));
    }

    @Test
    void listReturnsFullSnippetWhenShort() {
        when(sermonNoteRepository.findByUserIdOrderByUpdatedAtDesc(USER_ID))
            .thenReturn(List.of(existingNote("Short", "Just a short note.")));

        SermonNoteSummary res = service.list(USER_ID).get(0);

        assertEquals("Just a short note.", res.snippet());
    }
}
