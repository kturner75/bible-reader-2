package com.readthekjv.service;

import com.readthekjv.exception.BadRequestException;
import com.readthekjv.model.entity.SavedVerse;
import com.readthekjv.repository.SavedVerseRepository;
import com.readthekjv.repository.TagRepository;
import com.readthekjv.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LibraryServiceTest {

    private static final Long USER_ID = 42L;
    private static final int VERSE_ID = 26137;

    private SavedVerseRepository savedVerseRepository;
    private LibraryService service;

    @BeforeEach
    void setUp() {
        savedVerseRepository = mock(SavedVerseRepository.class);
        TagRepository tagRepository = mock(TagRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        service = new LibraryService(savedVerseRepository, tagRepository, userRepository);
    }

    @Test
    void updateNoteRefusesPastedEmbedOverTwelveAndDoesNotSave() {
        SavedVerse existing = new SavedVerse();
        existing.setVerseId(VERSE_ID);
        existing.setNote("old note");
        when(savedVerseRepository.findByUserIdAndVerseId(USER_ID, VERSE_ID))
                .thenReturn(Optional.of(existing));

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> service.updateNote(USER_ID, VERSE_ID, "See [e=1-13]"));
        assertTrue(ex.getMessage().contains("12"));
        assertTrue(ex.getMessage().contains("13"));
        verify(savedVerseRepository, never()).save(any());
        assertEquals("old note", existing.getNote());
    }
}
