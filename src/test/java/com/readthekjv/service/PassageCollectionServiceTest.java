package com.readthekjv.service;

import com.readthekjv.exception.BadRequestException;
import com.readthekjv.model.Verse;
import com.readthekjv.model.dto.CollectionReadResponse;
import com.readthekjv.model.dto.CollectionResponse;
import com.readthekjv.model.dto.PassageDetailResponse;
import com.readthekjv.model.entity.Passage;
import com.readthekjv.model.entity.PassageCollection;
import com.readthekjv.model.entity.User;
import com.readthekjv.repository.PassageCollectionRepository;
import com.readthekjv.repository.PassageRepository;
import com.readthekjv.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PassageCollectionServiceTest {

    private static final Long USER_ID = 42L;

    private PassageCollectionRepository collectionRepository;
    private UserRepository userRepository;
    private PassageRepository passageRepository;
    private PassageCollectionService service;
    private PassageService passageService;

    private Passage passageA;
    private Passage passageB;
    private UUID idA;
    private UUID idB;

    @BeforeEach
    void setUp() {
        collectionRepository = mock(PassageCollectionRepository.class);
        userRepository = mock(UserRepository.class);
        passageRepository = mock(PassageRepository.class);

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

        passageService = new PassageService(passageRepository, userRepository, bibleService);
        service = new PassageCollectionService(
                collectionRepository, userRepository, passageRepository, passageService);

        when(userRepository.getReferenceById(USER_ID)).thenReturn(new User());
        when(collectionRepository.saveAndFlush(any(PassageCollection.class)))
            .thenAnswer(inv -> {
                PassageCollection c = inv.getArgument(0);
                if (c.getId() == null) {
                    ReflectionTestUtils.setField(c, "id", 1L);
                }
                return c;
            });

        idA = UUID.randomUUID();
        idB = UUID.randomUUID();
        passageA = new Passage();
        ReflectionTestUtils.setField(passageA, "id", idA);
        passageA.setNaturalKey("1:2");
        passageA.setFromVerseId(1);
        passageA.setToVerseId(2);
        User owner = new User();
        ReflectionTestUtils.setField(owner, "id", USER_ID);
        passageA.setUser(owner);

        passageB = new Passage();
        ReflectionTestUtils.setField(passageB, "id", idB);
        passageB.setNaturalKey("5");
        passageB.setFromVerseId(5);
        passageB.setToVerseId(5);
        passageB.setUser(owner);

        when(passageRepository.findByIdAndUserId(idA, USER_ID)).thenReturn(Optional.of(passageA));
        when(passageRepository.findByIdAndUserId(idB, USER_ID)).thenReturn(Optional.of(passageB));
        when(passageRepository.findById(idA)).thenReturn(Optional.of(passageA));
        when(passageRepository.findById(idB)).thenReturn(Optional.of(passageB));
        when(passageRepository.findAllById(any())).thenAnswer(inv -> {
            Iterable<UUID> ids = inv.getArgument(0);
            List<Passage> found = new ArrayList<>();
            for (UUID id : ids) {
                if (idA.equals(id)) found.add(passageA);
                else if (idB.equals(id)) found.add(passageB);
            }
            return found;
        });
    }

    private PassageCollection existingCollection(String label, List<UUID> passageIds) {
        PassageCollection c = new PassageCollection();
        ReflectionTestUtils.setField(c, "id", 7L);
        c.setLabel(label);
        c.getPassageIds().addAll(passageIds);
        return c;
    }

    @Test
    void createPreservesPassageOrderIncludingRepeats() {
        CollectionResponse res = service.create(USER_ID, "Study", List.of(idB, idA, idB), List.of());

        ArgumentCaptor<PassageCollection> captor = ArgumentCaptor.forClass(PassageCollection.class);
        verify(collectionRepository).saveAndFlush(captor.capture());
        assertEquals(List.of(idB, idA, idB), captor.getValue().getPassageIds());
        assertEquals(List.of(idB, idA, idB), res.passageIds());
        assertEquals(4, res.verseCount()); // 1 + 2 + 1
    }

    @Test
    void createTrimsLabel() {
        CollectionResponse res = service.create(USER_ID, "  My List  ", List.of(idA), List.of());
        assertEquals("My List", res.label());
    }

    @Test
    void createRejectsUnknownPassage() {
        UUID missing = UUID.randomUUID();
        when(passageRepository.findByIdAndUserId(missing, USER_ID)).thenReturn(Optional.empty());
        when(passageRepository.findByIdAndUserIsNull(missing)).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class,
            () -> service.create(USER_ID, "Bad", List.of(missing), List.of()));
        verify(collectionRepository, never()).saveAndFlush(any());
    }

    @Test
    void createMapsDuplicateLabelViolationTo400() {
        when(collectionRepository.saveAndFlush(any(PassageCollection.class)))
            .thenThrow(new DataIntegrityViolationException("uq_passage_collections_user_label"));

        assertThrows(BadRequestException.class,
            () -> service.create(USER_ID, "Dup", List.of(idA), List.of()));
    }

    @Test
    void updateReplacesPassageListInPlace() {
        PassageCollection existing = existingCollection("Old", List.of(idA));
        List<UUID> managedList = existing.getPassageIds();
        when(collectionRepository.findByIdAndUserId(7L, USER_ID)).thenReturn(Optional.of(existing));

        service.update(USER_ID, 7L, "New", List.of(idB), List.of());

        assertSame(managedList, existing.getPassageIds());
        assertEquals(List.of(idB), existing.getPassageIds());
        assertEquals("New", existing.getLabel());
    }

    @Test
    void updateRefreshesUpdatedAtEvenWhenOnlyPassagesChange() {
        PassageCollection existing = existingCollection("Same Label", List.of(idA));
        OffsetDateTime stale = OffsetDateTime.now().minusDays(3);
        ReflectionTestUtils.setField(existing, "updatedAt", stale);
        when(collectionRepository.findByIdAndUserId(7L, USER_ID)).thenReturn(Optional.of(existing));

        service.update(USER_ID, 7L, "Same Label", List.of(idB), List.of());

        assertTrue(existing.getUpdatedAt().isAfter(stale));
    }

    @Test
    void notOwnedCollectionIs404() {
        when(collectionRepository.findByIdAndUserId(7L, USER_ID)).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class, () -> service.get(USER_ID, 7L));
        assertThrows(ResponseStatusException.class,
            () -> service.update(USER_ID, 7L, "X", List.of(idA), List.of()));
        assertThrows(ResponseStatusException.class, () -> service.delete(USER_ID, 7L));
        assertThrows(ResponseStatusException.class, () -> service.getHydrated(USER_ID, 7L));
    }

    @Test
    void getHydratedReturnsPassagesWithVersesInOrder() {
        PassageCollection existing = existingCollection("Cross-book", List.of(idB, idA));
        when(collectionRepository.findByIdAndUserId(7L, USER_ID)).thenReturn(Optional.of(existing));

        CollectionReadResponse res = service.getHydrated(USER_ID, 7L);

        assertEquals(2, res.passages().size());
        assertEquals("Exodus 1:1", res.passages().get(0).reference());
        assertEquals("Genesis 1:1–2", res.passages().get(1).reference());
        assertEquals(1, res.passages().get(0).verses().size());
        assertEquals(2, res.passages().get(1).verses().size());
    }

    @Test
    void deleteRemovesOwnedCollection() {
        PassageCollection existing = existingCollection("Gone", List.of(idA));
        when(collectionRepository.findByIdAndUserId(7L, USER_ID)).thenReturn(Optional.of(existing));

        service.delete(USER_ID, 7L);

        verify(collectionRepository).delete(existing);
    }

    @Test
    void createRejectsWhenExpandedVerseCountExceedsCap() {
        // passageA is 2 verses; 251 repeats → 502 > 500
        List<UUID> many = new ArrayList<>();
        for (int i = 0; i < 251; i++) many.add(idA);

        assertThrows(BadRequestException.class,
                () -> service.create(USER_ID, "Huge", many, List.of()));
        verify(collectionRepository, never()).saveAndFlush(any());
    }

    @Test
    void upsertCreatesUserPassage() {
        when(passageRepository.findByUserIsNullAndNaturalKey("3:4")).thenReturn(Optional.empty());
        when(passageRepository.findByUserIdAndNaturalKey(USER_ID, "3:4")).thenReturn(Optional.empty());
        when(passageRepository.findByUserIdOrderByCreatedAtDesc(USER_ID)).thenReturn(List.of());
        when(passageRepository.save(any(Passage.class))).thenAnswer(inv -> {
            Passage p = inv.getArgument(0);
            ReflectionTestUtils.setField(p, "id", UUID.randomUUID());
            return p;
        });

        PassageDetailResponse res = passageService.upsert(USER_ID, "3:4", "Creation");
        assertEquals("Creation", res.title());
        assertEquals("Genesis 2:1–2", res.reference());
    }

    @Test
    void upsertReusesEquivalentNoncanonicalNaturalKey() {
        Passage existing = new Passage();
        UUID existingId = UUID.randomUUID();
        ReflectionTestUtils.setField(existing, "id", existingId);
        existing.setNaturalKey("2,1"); // same ranges as canonical "1:2"
        existing.setFromVerseId(1);
        existing.setToVerseId(2);
        User owner = new User();
        ReflectionTestUtils.setField(owner, "id", USER_ID);
        existing.setUser(owner);

        when(passageRepository.findByUserIdAndNaturalKey(USER_ID, "1:2")).thenReturn(Optional.empty());
        when(passageRepository.findByUserIdOrderByCreatedAtDesc(USER_ID)).thenReturn(List.of(existing));
        when(passageRepository.save(any(Passage.class))).thenAnswer(inv -> inv.getArgument(0));

        PassageDetailResponse res = passageService.upsert(USER_ID, "1:2", "Title");
        assertEquals(existingId, res.id());
        assertEquals("1:2", existing.getNaturalKey());
        assertEquals("Title", existing.getTitle());
    }
}
