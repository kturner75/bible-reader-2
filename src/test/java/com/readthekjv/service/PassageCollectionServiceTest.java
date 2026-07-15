package com.readthekjv.service;

import com.readthekjv.exception.BadRequestException;
import com.readthekjv.model.Verse;
import com.readthekjv.model.dto.CollectionReadResponse;
import com.readthekjv.model.dto.CollectionResponse;
import com.readthekjv.model.entity.PassageCollection;
import com.readthekjv.model.entity.User;
import com.readthekjv.repository.PassageCollectionRepository;
import com.readthekjv.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PassageCollectionServiceTest {

    private static final Long USER_ID = 42L;

    private PassageCollectionRepository collectionRepository;
    private UserRepository userRepository;
    private PassageCollectionService service;

    @BeforeEach
    void setUp() {
        collectionRepository = mock(PassageCollectionRepository.class);
        userRepository = mock(UserRepository.class);

        // Real BibleService with a tiny two-book Bible (7 verses total)
        BibleService bibleService = new BibleService();
        bibleService.loadVerses(List.of(
            new Verse(1, "Genesis", 1, 1, 1, "In the beginning..."),
            new Verse(2, "Genesis", 1, 1, 2, "And the earth..."),
            new Verse(3, "Genesis", 1, 2, 1, "Thus the heavens..."),
            new Verse(4, "Genesis", 1, 2, 2, "And on the seventh day..."),
            new Verse(5, "Exodus", 2, 1, 1, "Now these are the names..."),
            new Verse(6, "Exodus", 2, 1, 2, "Reuben, Simeon..."),
            new Verse(7, "Exodus", 2, 1, 3, "And Levi...")
        ));

        service = new PassageCollectionService(collectionRepository, userRepository, bibleService);

        when(userRepository.getReferenceById(USER_ID)).thenReturn(new User());
        when(collectionRepository.saveAndFlush(any(PassageCollection.class)))
            .thenAnswer(inv -> {
                PassageCollection c = inv.getArgument(0);
                if (c.getId() == null) {
                    // DB assigns the BIGSERIAL id on insert; simulate it for DTO mapping
                    ReflectionTestUtils.setField(c, "id", 1L);
                }
                return c;
            });
    }

    private PassageCollection existingCollection(String label, List<Integer> verseIds) {
        PassageCollection c = new PassageCollection();
        ReflectionTestUtils.setField(c, "id", 7L);
        c.setLabel(label);
        c.getVerseIds().addAll(verseIds);
        return c;
    }

    @Test
    void createPreservesInsertionOrderIncludingOutOfCanonicalOrder() {
        CollectionResponse res = service.create(USER_ID, "Study", List.of(6, 1, 3));

        ArgumentCaptor<PassageCollection> captor = ArgumentCaptor.forClass(PassageCollection.class);
        verify(collectionRepository).saveAndFlush(captor.capture());
        assertEquals(List.of(6, 1, 3), captor.getValue().getVerseIds());
        assertEquals(List.of(6, 1, 3), res.verseIds());
    }

    @Test
    void createTrimsLabel() {
        CollectionResponse res = service.create(USER_ID, "  My List  ", List.of(1));
        assertEquals("My List", res.label());
    }

    @Test
    void createRejectsOutOfRangeVerseId() {
        assertThrows(BadRequestException.class,
            () -> service.create(USER_ID, "Bad", List.of(1, 99)));
        assertThrows(BadRequestException.class,
            () -> service.create(USER_ID, "Bad", List.of(0)));
        verify(collectionRepository, never()).saveAndFlush(any());
    }

    @Test
    void createMapsDuplicateLabelViolationTo400() {
        when(collectionRepository.saveAndFlush(any(PassageCollection.class)))
            .thenThrow(new DataIntegrityViolationException("uq_passage_collections_user_label"));

        assertThrows(BadRequestException.class,
            () -> service.create(USER_ID, "Dup", List.of(1)));
    }

    @Test
    void updateReplacesVerseListInPlace() {
        PassageCollection existing = existingCollection("Old", List.of(1, 2));
        List<Integer> managedList = existing.getVerseIds();
        when(collectionRepository.findByIdAndUserId(7L, USER_ID)).thenReturn(Optional.of(existing));

        service.update(USER_ID, 7L, "New", List.of(5, 3));

        assertSame(managedList, existing.getVerseIds());
        assertEquals(List.of(5, 3), existing.getVerseIds());
        assertEquals("New", existing.getLabel());
    }

    @Test
    void updateRefreshesUpdatedAtEvenWhenOnlyVersesChange() {
        PassageCollection existing = existingCollection("Same Label", List.of(1, 2));
        OffsetDateTime stale = OffsetDateTime.now().minusDays(3);
        ReflectionTestUtils.setField(existing, "updatedAt", stale);
        when(collectionRepository.findByIdAndUserId(7L, USER_ID)).thenReturn(Optional.of(existing));

        // Same label, different verses — only the @ElementCollection changes,
        // so the service must dirty the parent row explicitly
        service.update(USER_ID, 7L, "Same Label", List.of(3));

        assertTrue(existing.getUpdatedAt().isAfter(stale));
    }

    @Test
    void notOwnedCollectionIs404() {
        when(collectionRepository.findByIdAndUserId(7L, USER_ID)).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class, () -> service.get(USER_ID, 7L));
        assertThrows(ResponseStatusException.class, () -> service.update(USER_ID, 7L, "X", List.of(1)));
        assertThrows(ResponseStatusException.class, () -> service.delete(USER_ID, 7L));
        assertThrows(ResponseStatusException.class, () -> service.getHydrated(USER_ID, 7L));
    }

    @Test
    void getHydratedReturnsVersesInStoredOrderWithMetadata() {
        PassageCollection existing = existingCollection("Cross-book", List.of(5, 1));
        when(collectionRepository.findByIdAndUserId(7L, USER_ID)).thenReturn(Optional.of(existing));

        CollectionReadResponse res = service.getHydrated(USER_ID, 7L);

        assertEquals(2, res.verses().size());
        assertEquals("Exodus 1:1", res.verses().get(0).reference());
        assertEquals("Genesis 1:1", res.verses().get(1).reference());
        assertEquals("Now these are the names...", res.verses().get(0).text());
    }

    @Test
    void deleteRemovesOwnedCollection() {
        PassageCollection existing = existingCollection("Gone", List.of(1));
        when(collectionRepository.findByIdAndUserId(7L, USER_ID)).thenReturn(Optional.of(existing));

        service.delete(USER_ID, 7L);

        verify(collectionRepository).delete(existing);
    }
}
