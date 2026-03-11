package com.readthekjv.service;

import com.readthekjv.model.ChapterInfo;
import com.readthekjv.model.entity.ReadingPlan;
import com.readthekjv.model.entity.ReadingPlanDay;
import com.readthekjv.repository.ReadingPlanDayRepository;
import com.readthekjv.repository.ReadingPlanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Seeds the three pre-built reading plans at startup.
 * Idempotent — skips any plan whose slug already exists in the DB.
 *
 * Uses ApplicationReadyEvent so BibleDataLoader.loadData() (@PostConstruct)
 * has already populated BibleService before collectChapters() is called.
 */
@Component
public class ReadingPlanSeeder {

    private static final Logger log = LoggerFactory.getLogger(ReadingPlanSeeder.class);

    private final ReadingPlanRepository    planRepo;
    private final ReadingPlanDayRepository dayRepo;
    private final BibleService             bibleService;

    public ReadingPlanSeeder(ReadingPlanRepository planRepo,
                             ReadingPlanDayRepository dayRepo,
                             BibleService bibleService) {
        this.planRepo    = planRepo;
        this.dayRepo     = dayRepo;
        this.bibleService = bibleService;
    }

    private record PlanSpec(String slug, String title, int totalDays,
                             int fromBookId, int toBookId) {}

    private static final List<PlanSpec> PLANS = List.of(
        new PlanSpec("psalms-in-a-month",  "Psalms in a Month",         30,   19, 19),
        new PlanSpec("nt-in-90-days",      "New Testament in 90 Days",  90,   40, 66),
        new PlanSpec("bible-in-a-year",    "Read the Bible in a Year",  365,   1, 66)
    );

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seed() {
        log.info("Seeding reading plans...");
        for (PlanSpec spec : PLANS) {
            if (planRepo.findBySlug(spec.slug()).isPresent()) {
                log.debug("Plan '{}' already exists — skipping", spec.slug());
                continue;
            }

            List<ChapterWithBook> chapters = collectChapters(spec.fromBookId(), spec.toBookId());
            int totalChapters = chapters.size();

            ReadingPlan plan = new ReadingPlan();
            plan.setSlug(spec.slug());
            plan.setTitle(spec.title());
            plan.setTotalDays(spec.totalDays());
            planRepo.save(plan);

            List<ReadingPlanDay> days = new ArrayList<>();
            for (int day = 1; day <= spec.totalDays(); day++) {
                // Integer distribution: evenly spreads remainder chapters across the plan
                int startIdx = (day - 1) * totalChapters / spec.totalDays();
                int endIdx   =  day      * totalChapters / spec.totalDays();
                if (startIdx >= endIdx) continue; // shouldn't happen for these three plans

                List<ChapterWithBook> dayChapters = chapters.subList(startIdx, endIdx);
                ChapterInfo last = dayChapters.get(dayChapters.size() - 1).info();

                ReadingPlanDay rpd = new ReadingPlanDay();
                rpd.setPlan(plan);
                rpd.setDayNumber(day);
                rpd.setLabel(buildLabel(dayChapters));
                rpd.setFromVerseId(dayChapters.get(0).info().firstVerseId());
                rpd.setToVerseId(last.firstVerseId() + last.verseCount() - 1);
                days.add(rpd);
            }

            dayRepo.saveAll(days);
            log.info("Seeded plan '{}' with {} days", spec.slug(), days.size());
        }
        log.info("Reading plan seeding complete.");
    }

    // ── Chapter collection ────────────────────────────────────────────────────

    private record ChapterWithBook(String bookName, ChapterInfo info) {}

    private List<ChapterWithBook> collectChapters(int fromBookId, int toBookId) {
        List<ChapterWithBook> result = new ArrayList<>();
        for (int bookId = fromBookId; bookId <= toBookId; bookId++) {
            bibleService.getBook(bookId).ifPresent(book ->
                bibleService.getChapters(book.id()).forEach(ci ->
                    result.add(new ChapterWithBook(book.name(), ci))
                )
            );
        }
        return result;
    }

    // ── Label generation ─────────────────────────────────────────────────────

    /**
     * Groups consecutive same-book chapters and formats as:
     *   single chapter  → "Psalms 23"
     *   chapter range   → "Psalms 1–5"  (en-dash U+2013)
     *   multi-book day  → "Genesis 49–50; Exodus 1"
     */
    private String buildLabel(List<ChapterWithBook> dayChapters) {
        List<String> segments = new ArrayList<>();
        int i = 0;
        while (i < dayChapters.size()) {
            String bookName    = dayChapters.get(i).bookName();
            int    firstChNum  = dayChapters.get(i).info().chapter();
            int    lastChNum   = firstChNum;
            int    j           = i + 1;
            while (j < dayChapters.size() &&
                   dayChapters.get(j).bookName().equals(bookName)) {
                lastChNum = dayChapters.get(j).info().chapter();
                j++;
            }
            segments.add(firstChNum == lastChNum
                    ? bookName + " " + firstChNum
                    : bookName + " " + firstChNum + "\u2013" + lastChNum);
            i = j;
        }
        return String.join("; ", segments);
    }
}
