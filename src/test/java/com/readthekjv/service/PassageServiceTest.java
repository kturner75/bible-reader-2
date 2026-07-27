package com.readthekjv.service;

import com.readthekjv.exception.BadRequestException;
import com.readthekjv.model.Verse;
import com.readthekjv.model.dto.PassageDetailResponse;
import com.readthekjv.model.entity.Passage;
import com.readthekjv.model.entity.User;
import com.readthekjv.repository.PassageRepository;
import com.readthekjv.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PassageServiceTest {

    private static final Long USER_ID = 10L;

    private PassageRepository passageRepository;
    private UserRepository userRepository;
    private PassageService service;

    @BeforeEach
    void setUp() {
        passageRepository = mock(PassageRepository.class);
        userRepository = mock(UserRepository.class);

        BibleService bibleService = new BibleService();
        bibleService.loadVerses(List.of(
            new Verse(1, "Genesis", 1, 1, 1, "In the beginning"),
            new Verse(2, "Genesis", 1, 1, 2, "And the earth"),
            new Verse(3, "Genesis", 1, 1, 3, "And God said"),
            new Verse(4, "Genesis", 1, 2, 1, "Thus the heavens"),
            new Verse(5, "Exodus", 2, 1, 1, "Now these are the names")
        ));

        service = new PassageService(passageRepository, userRepository, bibleService);

        when(userRepository.getReferenceById(USER_ID)).thenReturn(new User());

        // Default: no existing passage
        when(passageRepository.findByUserIdAndNaturalKey(eq(USER_ID), any())).thenReturn(Optional.empty());
        when(passageRepository.findByUserIdOrderByCreatedAtDesc(USER_ID)).thenReturn(List.of());
        when(passageRepository.findByUserIsNullOrderBySortOrderAsc()).thenReturn(List.of());

        // save assigns a UUID to new passages
        when(passageRepository.save(any(Passage.class))).thenAnswer(inv -> {
            Passage p = inv.getArgument(0);
            if (p.getId() == null) ReflectionTestUtils.setField(p, "id", UUID.randomUUID());
            return p;
        });
    }

    // ── upsert ────────────────────────────────────────────────────────────────

    @Test
    void upsertCreatesNewPassageForNewKey() {
        PassageDetailResponse result = service.upsert(USER_ID, "1:3", null);

        assertNotNull(result.id());
        assertEquals("1:3", result.naturalKey());
        assertEquals(1, result.fromVerseId());
        assertEquals(3, result.toVerseId());
        assertFalse(result.global());
        verify(passageRepository).save(any(Passage.class));
    }

    @Test
    void upsertSingleVerseNaturalKeyIsSingleNumber() {
        PassageDetailResponse result = service.upsert(USER_ID, "1", null);

        assertEquals("1", result.naturalKey());
        assertEquals(1, result.fromVerseId());
        assertEquals(1, result.toVerseId());
    }

    @Test
    void upsertIsIdempotentForSameKey() {
        Passage existing = passage("1:3", 1, 3);
        when(passageRepository.findByUserIdAndNaturalKey(USER_ID, "1:3"))
                .thenReturn(Optional.of(existing));

        service.upsert(USER_ID, "1:3", null);

        // save still called to apply any title update, but no new Passage created
        verify(passageRepository).save(existing);
        verify(userRepository, never()).getReferenceById(any());
    }

    @Test
    void upsertSetsTitle() {
        PassageDetailResponse result = service.upsert(USER_ID, "1:2", "My Passage");

        assertEquals("My Passage", result.title());
    }

    @Test
    void upsertBlankTitleBecomesNull() {
        PassageDetailResponse result = service.upsert(USER_ID, "1:2", "  ");

        assertNull(result.title());
    }

    @Test
    void upsertRejectsOversizedKey() {
        // 501 consecutive IDs starting from 1 — exceeds MAX_PASSAGE_VERSES (500)
        // Build a key like "1:501"
        assertThrows(BadRequestException.class,
                () -> service.upsert(USER_ID, "1:501", null));
    }

    @Test
    void upsertRejectsInvalidKey() {
        assertThrows(BadRequestException.class,
                () -> service.upsert(USER_ID, "not-a-key", null));
    }

    // ── listCatalog ───────────────────────────────────────────────────────────

    @Test
    void listCatalogReturnUserPassagesBeforeGlobals() {
        Passage user1 = passage("1:2", 1, 2);
        Passage global1 = globalPassage("3:4", 3, 4);

        when(passageRepository.findByUserIdOrderByCreatedAtDesc(USER_ID)).thenReturn(List.of(user1));
        when(passageRepository.findByUserIsNullOrderBySortOrderAsc()).thenReturn(List.of(global1));

        List<PassageDetailResponse> result = service.listCatalog(USER_ID, null);

        assertEquals(2, result.size());
        assertFalse(result.get(0).global());  // user passage first
        assertTrue(result.get(1).global());   // global second
    }

    @Test
    void listCatalogFiltersOnTitle() {
        Passage match = passageWithTitle("1:2", 1, 2, "Sermon on the Mount");
        Passage noMatch = passageWithTitle("3:4", 3, 4, "Creation");

        when(passageRepository.findByUserIdOrderByCreatedAtDesc(USER_ID))
                .thenReturn(List.of(match, noMatch));

        List<PassageDetailResponse> result = service.listCatalog(USER_ID, "sermon");

        assertEquals(1, result.size());
        assertEquals("Sermon on the Mount", result.get(0).title());
    }

    @Test
    void listCatalogNullQueryReturnsAll() {
        Passage p1 = passage("1", 1, 1);
        Passage p2 = passage("2", 2, 2);
        when(passageRepository.findByUserIdOrderByCreatedAtDesc(USER_ID)).thenReturn(List.of(p1, p2));

        assertEquals(2, service.listCatalog(USER_ID, null).size());
    }

    // ── updateTitle ──────────────────────────────────────────────────────────

    @Test
    void updateTitleChangesTitle() {
        Passage p = passage("1:3", 1, 3);
        when(passageRepository.findByIdAndUserId(p.getId(), USER_ID)).thenReturn(Optional.of(p));

        PassageDetailResponse result = service.updateTitle(USER_ID, p.getId(), "New Title");

        assertEquals("New Title", result.title());
    }

    @Test
    void updateTitleBlankBecomesNull() {
        Passage p = passageWithTitle("1:3", 1, 3, "Old Title");
        when(passageRepository.findByIdAndUserId(p.getId(), USER_ID)).thenReturn(Optional.of(p));

        PassageDetailResponse result = service.updateTitle(USER_ID, p.getId(), "  ");

        assertNull(result.title());
    }

    @Test
    void updateTitleThrows404ForWrongUser() {
        UUID id = UUID.randomUUID();
        when(passageRepository.findByIdAndUserId(id, USER_ID)).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class,
                () -> service.updateTitle(USER_ID, id, "Title"));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static Passage passage(String naturalKey, int from, int to) {
        Passage p = new Passage();
        ReflectionTestUtils.setField(p, "id", UUID.randomUUID());
        p.setNaturalKey(naturalKey);
        p.setFromVerseId(from);
        p.setToVerseId(to);
        p.setUser(new User());
        return p;
    }

    private static Passage passageWithTitle(String naturalKey, int from, int to, String title) {
        Passage p = passage(naturalKey, from, to);
        p.setTitle(title);
        return p;
    }

    private static Passage globalPassage(String naturalKey, int from, int to) {
        Passage p = new Passage();
        ReflectionTestUtils.setField(p, "id", UUID.randomUUID());
        p.setNaturalKey(naturalKey);
        p.setFromVerseId(from);
        p.setToVerseId(to);
        // user == null → global
        return p;
    }
}
