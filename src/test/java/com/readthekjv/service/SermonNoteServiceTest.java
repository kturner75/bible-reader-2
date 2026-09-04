package com.readthekjv.service;

import com.readthekjv.exception.BadRequestException;
import com.readthekjv.model.Book;
import com.readthekjv.model.dto.SermonNoteResponse;
import com.readthekjv.model.dto.SermonNoteSummary;
import com.readthekjv.model.entity.SermonNote;
import com.readthekjv.model.entity.SermonNoteRef;
import com.readthekjv.model.entity.User;
import com.readthekjv.repository.SermonNoteRefRepository;
import com.readthekjv.repository.SermonNoteRepository;
import com.readthekjv.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class SermonNoteServiceTest {

    private static final Long USER_ID = 42L;
    private static final UUID NOTE_ID = UUID.randomUUID();
    private static final OffsetDateTime EPOCH = OffsetDateTime.parse("1970-01-01T00:00:00Z");

    private SermonNoteRepository sermonNoteRepository;
    private SermonNoteRefRepository refRepository;
    private UserRepository userRepository;
    private NoteScriptureIndexer indexer;
    private BibleService bibleService;
    private SermonNoteService service;

    @BeforeEach
    void setUp() {
        sermonNoteRepository = mock(SermonNoteRepository.class);
        refRepository = mock(SermonNoteRefRepository.class);
        userRepository = mock(UserRepository.class);
        indexer = mock(NoteScriptureIndexer.class);
        bibleService = mock(BibleService.class);

        service = new SermonNoteService(
                sermonNoteRepository, refRepository, userRepository, indexer, bibleService);

        when(userRepository.getReferenceById(USER_ID)).thenReturn(new User());
        when(indexer.extract(any())).thenReturn(Set.of());
        when(refRepository.findForNotes(any())).thenReturn(List.of());
        when(bibleService.getBooks()).thenReturn(List.of());
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

    private void whenSearchReturns(SermonNote... notes) {
        when(sermonNoteRepository.search(any(), any(), any(), anyInt(), any(), any(Sort.class)))
            .thenReturn(List.of(notes));
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
    void deleteRemovesOwnedNoteAndItsDerivedRefs() {
        SermonNote existing = existingNote("Gone", "text");
        when(sermonNoteRepository.findByIdAndUserId(NOTE_ID, USER_ID)).thenReturn(Optional.of(existing));

        service.delete(USER_ID, NOTE_ID);

        verify(refRepository).deleteForNote(NOTE_ID);
        verify(sermonNoteRepository).delete(existing);
    }

    @Test
    void listMapsToSummariesWithTruncatedSnippet() {
        String longNote = "word ".repeat(50).trim();
        whenSearchReturns(existingNote("Long Note", longNote));

        List<SermonNoteSummary> summaries = service.list(USER_ID);

        assertEquals(1, summaries.size());
        SermonNoteSummary res = summaries.get(0);
        assertEquals("Long Note", res.title());
        assertTrue(res.snippet().length() <= 141, "snippet was " + res.snippet().length());
        assertTrue(res.snippet().endsWith("…"));
    }

    @Test
    void listReturnsFullSnippetWhenShort() {
        whenSearchReturns(existingNote("Short", "Just a short note."));

        SermonNoteSummary res = service.list(USER_ID).get(0);

        assertEquals("Just a short note.", res.snippet());
    }

    // ── Finder ────────────────────────────────────────────────────────────────

    @Test
    void listPassesNoFiltersAndNewestFirst() {
        whenSearchReturns();

        service.list(USER_ID);

        // Filters are expressed as match-everything values, never NULL: Postgres cannot
        // type a bare parameter in "? IS NULL".
        verify(sermonNoteRepository).search(eq(USER_ID), eq("%"), any(), eq(-1), eq(EPOCH),
                eq(Sort.by(Sort.Direction.DESC, "updatedAt")));
    }

    @Test
    void searchLowercasesAndWrapsTheQueryForLike() {
        whenSearchReturns();

        service.search(USER_ID, "  Shepherd  ", null, null, null);

        verify(sermonNoteRepository).search(eq(USER_ID), eq("%shepherd%"), any(), eq(-1), eq(EPOCH), any());
    }

    @Test
    void likeWildcardsInTheQueryAreEscapedSoTheyMatchLiterally() {
        whenSearchReturns();

        service.search(USER_ID, "100%", null, null, null);

        // Unescaped, "%" would match every note and disagree with the client's literal highlight.
        verify(sermonNoteRepository).search(any(), eq("%100\\%%"), any(), anyInt(), any(), any());
    }

    @Test
    void underscoreAndBackslashAreEscapedToo() {
        whenSearchReturns();

        service.search(USER_ID, "a_b\\c", null, null, null);

        verify(sermonNoteRepository).search(any(), eq("%a\\_b\\\\c%"), any(), anyInt(), any(), any());
    }

    @Test
    void refTotalKeepsTheUncappedCountWhenChipsAreCapped() {
        SermonNote note = existingNote("Wide", "many refs");
        whenSearchReturns(note);
        List<SermonNoteRef> rows = new java.util.ArrayList<>();
        for (int chapter = 1; chapter <= 20; chapter++) {
            rows.add(new SermonNoteRef(note, 19, chapter));
        }
        when(refRepository.findForNotes(any())).thenReturn(rows);
        when(bibleService.getBook(19)).thenReturn(Optional.of(new Book(19, "Psalms", 150, 13934, 16463)));

        SermonNoteSummary summary = service.list(USER_ID).get(0);

        assertEquals(12, summary.refs().size(), "chips stay capped for the card");
        assertEquals(20, summary.refTotal(), "but the card's +N must reflect the real total");
    }

    @Test
    void searchWidensToBooksWhoseNameMatchesTheQuery() {
        when(bibleService.getBooks()).thenReturn(List.of(
                new Book(19, "Psalms", 150, 13934, 16463),
                new Book(43, "John", 21, 26046, 26924),
                new Book(62, "1 John", 5, 30518, 30622)));
        whenSearchReturns();

        service.search(USER_ID, "john", null, null, null);

        ArgumentCaptor<java.util.Collection<Integer>> books = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(sermonNoteRepository).search(any(), any(), books.capture(), anyInt(), any(), any());
        assertEquals(List.of(43, 62), List.copyOf(books.getValue()));
    }

    @Test
    void searchWithNoBookNameMatchSendsASentinelNotAnEmptyList() {
        when(bibleService.getBooks()).thenReturn(List.of(new Book(19, "Psalms", 150, 13934, 16463)));
        whenSearchReturns();

        service.search(USER_ID, "shepherd", null, null, null);

        ArgumentCaptor<java.util.Collection<Integer>> books = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(sermonNoteRepository).search(any(), any(), books.capture(), anyInt(), any(), any());
        assertFalse(books.getValue().isEmpty(), "JPQL IN () is not legal — must send a sentinel");
        assertEquals(List.of(-1), List.copyOf(books.getValue()));
    }

    @Test
    void searchTranslatesTheRollingWindowAndSortOptions() {
        whenSearchReturns();

        service.search(USER_ID, null, null, "30d", "title");

        ArgumentCaptor<OffsetDateTime> since = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(sermonNoteRepository).search(any(), any(), any(), anyInt(), since.capture(),
                eq(Sort.by(Sort.Direction.ASC, "title")));
        assertTrue(since.getValue().isBefore(OffsetDateTime.now().minusDays(29)));
        assertTrue(since.getValue().isAfter(OffsetDateTime.now().minusDays(31)));
    }

    @Test
    void unknownWindowAndSortFallBackRatherThanFailing() {
        whenSearchReturns();

        service.search(USER_ID, null, null, "since-the-flood", "by-vibes");

        verify(sermonNoteRepository).search(any(), any(), any(), anyInt(), eq(EPOCH),
                eq(Sort.by(Sort.Direction.DESC, "updatedAt")));
    }

    @Test
    void summariesCarryScriptureChipsInBibleOrder() {
        SermonNote note = existingNote("Funeral", "See [v=14237-14242] and [v=26792]");
        whenSearchReturns(note);
        when(bibleService.getBook(19)).thenReturn(Optional.of(new Book(19, "Psalms", 150, 13934, 16463)));
        when(bibleService.getBook(43)).thenReturn(Optional.of(new Book(43, "John", 21, 26046, 26924)));
        when(refRepository.findForNotes(any())).thenReturn(List.of(
                new SermonNoteRef(note, 19, 23),
                new SermonNoteRef(note, 43, 14)));

        List<SermonNoteSummary.ScriptureRef> refs = service.list(USER_ID).get(0).refs();

        assertEquals(List.of("Psalm 23", "John 14"), refs.stream().map(SermonNoteSummary.ScriptureRef::label).toList());
    }

    @Test
    void oneChapterBookChipOmitsTheChapterNumber() {
        SermonNote note = existingNote("Jude", "[v=30675]");
        whenSearchReturns(note);
        when(bibleService.getBook(65)).thenReturn(Optional.of(new Book(65, "Jude", 1, 30675, 30699)));
        when(refRepository.findForNotes(any())).thenReturn(List.of(new SermonNoteRef(note, 65, 1)));

        assertEquals("Jude", service.list(USER_ID).get(0).refs().get(0).label());
    }

    @Test
    void snippetShowsTheChapterLabelWhereATokenStood() {
        SermonNote note = existingNote("Jude", "Short letter, sharp edge. [v=30675] sets the address.");
        whenSearchReturns(note);
        when(bibleService.getVerse(30675)).thenReturn(Optional.of(
                new com.readthekjv.model.Verse(30675, "Jude", 65, 1, 1, "text")));
        when(bibleService.getBook(65)).thenReturn(Optional.of(new Book(65, "Jude", 1, 30675, 30699)));

        String snippet = service.list(USER_ID).get(0).snippet();

        assertEquals("Short letter, sharp edge. Jude sets the address.", snippet);
    }

    @Test
    void anUnresolvableTokenIsDroppedRatherThanShownRaw() {
        SermonNote note = existingNote("Odd", "Before [v=999999] after.");
        whenSearchReturns(note);

        assertFalse(service.list(USER_ID).get(0).snippet().contains("[v="));
    }

    @Test
    void createReindexesScriptureRefs() {
        when(indexer.extract("[v=26136]")).thenReturn(
                Set.of(new NoteScriptureIndexer.BookChapter(43, 3)));

        service.create(USER_ID, "Title", "[v=26136]");

        verify(refRepository).deleteForNote(any(UUID.class));
        ArgumentCaptor<List<SermonNoteRef>> rows = ArgumentCaptor.forClass(List.class);
        verify(refRepository).saveAll(rows.capture());
        assertEquals(1, rows.getValue().size());
        assertEquals(43, rows.getValue().get(0).getBookId());
        assertEquals(3, rows.getValue().get(0).getChapter());
    }

    @Test
    void reindexClearsRowsWhenTheBodyNoLongerCitesAnything() {
        SermonNote existing = existingNote("Old", "[v=26136]");
        when(sermonNoteRepository.findByIdAndUserId(NOTE_ID, USER_ID)).thenReturn(Optional.of(existing));
        when(indexer.extract("no scripture here")).thenReturn(Set.of());

        service.update(USER_ID, NOTE_ID, "New", "no scripture here");

        verify(refRepository).deleteForNote(NOTE_ID);
        verify(refRepository, never()).saveAll(any());
    }

    @Test
    void scriptureFilterOffersOnlyBooksTheUserHasCited() {
        when(refRepository.findBookIdsForUser(USER_ID)).thenReturn(List.of(19, 43));
        when(bibleService.getBook(19)).thenReturn(Optional.of(new Book(19, "Psalms", 150, 13934, 16463)));
        when(bibleService.getBook(43)).thenReturn(Optional.of(new Book(43, "John", 21, 26046, 26924)));

        List<SermonNoteSummary.ScriptureRef> options = service.scriptureFilterOptions(USER_ID);

        assertEquals(List.of("Psalms", "John"), options.stream().map(SermonNoteSummary.ScriptureRef::label).toList());
        assertEquals(List.of(19, 43), options.stream().map(SermonNoteSummary.ScriptureRef::bookId).toList());
    }
}
