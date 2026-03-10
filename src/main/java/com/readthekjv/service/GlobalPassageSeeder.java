package com.readthekjv.service;

import com.readthekjv.model.entity.Passage;
import com.readthekjv.repository.PassageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Seeds the curated global passages (user_id IS NULL) at startup.
 * Each entry is idempotent — skipped if the natural_key already exists globally.
 *
 * Uses ApplicationReadyEvent so BibleDataLoader.loadData() (@PostConstruct) has
 * already populated BibleService before parseAndResolve() is called here.
 */
@Component
public class GlobalPassageSeeder {

    private static final Logger log = LoggerFactory.getLogger(GlobalPassageSeeder.class);

    private final PassageRepository passageRepo;
    private final BibleService      bibleService;

    public GlobalPassageSeeder(PassageRepository passageRepo, BibleService bibleService) {
        this.passageRepo = passageRepo;
        this.bibleService = bibleService;
    }

    private record SeedPassage(String title, String fromRef, String toRef, int sortOrder) {}

    private static final List<SeedPassage> SEEDS = List.of(
        new SeedPassage("Psalm 23",          "Psalm 23:1",      "Psalm 23:6",      1),
        new SeedPassage("The Lord's Prayer", "Matthew 6:9",     "Matthew 6:13",    2),
        new SeedPassage("The Beatitudes",    "Matthew 5:3",     "Matthew 5:12",    3),
        new SeedPassage("John 3:16",         "John 3:16",       "John 3:16",       4),
        new SeedPassage("Proverbs 3:5–6",    "Proverbs 3:5",    "Proverbs 3:6",    5),
        new SeedPassage("Philippians 4:6–7", "Philippians 4:6", "Philippians 4:7", 6),
        new SeedPassage("Romans 8:38–39",    "Romans 8:38",     "Romans 8:39",     7),
        new SeedPassage("John 1:1–5",        "John 1:1",        "John 1:5",        8)
    );

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seed() {
        log.info("Seeding global passages...");
        int created = 0;
        for (SeedPassage sp : SEEDS) {
            int fromId = resolveId(sp.fromRef());
            int toId   = resolveId(sp.toRef());
            if (fromId < 1 || toId < 1) {
                log.warn("Could not resolve verse IDs for '{}' ({} → {}) — skipping",
                         sp.title(), sp.fromRef(), sp.toRef());
                continue;
            }
            String naturalKey = fromId == toId
                    ? String.valueOf(fromId)
                    : fromId + ":" + toId;

            if (passageRepo.findByUserIsNullAndNaturalKey(naturalKey).isPresent()) {
                log.debug("Global passage '{}' already exists — skipping", sp.title());
                continue;
            }

            Passage p = new Passage();
            // user remains null — this is a global/shared passage
            p.setNaturalKey(naturalKey);
            p.setFromVerseId(fromId);
            p.setToVerseId(toId);
            p.setTitle(sp.title());
            p.setSortOrder(sp.sortOrder());
            passageRepo.save(p);
            log.info("Seeded global passage '{}' (naturalKey={})", sp.title(), naturalKey);
            created++;
        }
        log.info("Global passage seeding complete — {} new row(s) created", created);
    }

    private int resolveId(String ref) {
        return bibleService.parseAndResolve(ref).orElse(-1);
    }
}
