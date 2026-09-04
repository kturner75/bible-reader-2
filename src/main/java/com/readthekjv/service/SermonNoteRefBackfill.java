package com.readthekjv.service;

import com.readthekjv.model.entity.SermonNote;
import com.readthekjv.repository.SermonNoteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Indexes sermon notes written before the finder slice, so their scripture chips and the
 * book filter work without a hand-run script.
 *
 * Runs once per boot and walks a cursor over note ids, indexing anything with no rows in
 * {@code sermon_note_refs}. Notes that genuinely cite nothing keep no rows and so are
 * re-scanned on the next boot — a regex over a body that finds nothing, which is the price
 * of not carrying an indexed_at marker. See {@code docs/architecture/notes-finder-search.md}
 * for why that marker would have corrupted {@code updated_at}.
 *
 * The transactional work lives on {@link SermonNoteService}: a @Transactional method called
 * from inside this class would bypass its own proxy and run without a transaction at all.
 */
@Component
public class SermonNoteRefBackfill {

    private static final Logger log = LoggerFactory.getLogger(SermonNoteRefBackfill.class);

    /** Bodies are up to 20,000 chars; a chunk keeps the working set small. */
    private static final int CHUNK = 200;

    /** Sorts below every generated UUID, so the first chunk starts at the beginning. */
    private static final UUID START = new UUID(0L, 0L);

    private final SermonNoteRepository sermonNoteRepository;
    private final SermonNoteService sermonNoteService;

    public SermonNoteRefBackfill(SermonNoteRepository sermonNoteRepository,
                                 SermonNoteService sermonNoteService) {
        this.sermonNoteRepository = sermonNoteRepository;
        this.sermonNoteService = sermonNoteService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void backfillOnStartup() {
        int indexed = 0;
        UUID cursor = START;
        while (true) {
            List<SermonNote> chunk =
                    sermonNoteRepository.findUnindexedAfter(cursor, PageRequest.of(0, CHUNK));
            if (chunk.isEmpty()) {
                break;
            }
            cursor = chunk.get(chunk.size() - 1).getId();
            try {
                // One transaction per chunk: a boot interrupted midway resumes from the
                // unindexed remainder next time.
                indexed += sermonNoteService.reindexByIds(chunk.stream().map(SermonNote::getId).toList());
            } catch (RuntimeException e) {
                log.warn("Could not index a chunk of sermon note scripture refs", e);
            }
        }
        if (indexed > 0) {
            log.info("Backfilled scripture refs for {} sermon note(s)", indexed);
        }
    }
}
