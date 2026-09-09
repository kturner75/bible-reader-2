package com.readthekjv.service;

import com.readthekjv.model.Book;
import com.readthekjv.model.ChapterInfo;
import com.readthekjv.model.Verse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One-shot bulk pregeneration of the TTS corpus, run from the command line rather
 * than on the serving path:
 *
 * <pre>
 * TTS_PROVIDER=xai TTS_VOICE=helios \
 *   mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Dtts.max-concurrent-generations=8" \
 *     -Dspring-boot.run.arguments="--tts.pregen.enabled=true --tts.pregen.dry-run=true"
 * </pre>
 *
 * <p><strong>Resumable by construction.</strong> The work list is filtered against a
 * single LIST of the bucket, so a run that dies halfway costs nothing to restart —
 * it simply finds fewer gaps. There is no checkpoint file to corrupt.
 *
 * <p><strong>The namespace is the whole game.</strong> Clips land under
 * {@code audio/{provider}/{voice}/} and are only ever read back by a server running
 * that same provider and voice, so a run under the wrong config produces objects
 * nothing will serve. The run logs its namespace and refuses to start without
 * {@code tts.pregen.confirm-namespace} matching it.
 *
 * <p><strong>Subscription-only, by construction.</strong> Bulk generation runs under
 * {@link TtsService.BearerPolicy#OAUTH_ONLY} with no opt-out: no SuperGrok token means
 * no generation, and an exhausted or rejected subscription mid-run aborts rather than
 * falling back to the metered {@code XAI_API_KEY}. A 31k-clip run is exactly the case
 * where a silent fallback turns into a large invoice.
 *
 * <p>Announcements are generated before verses: they are 282 clips against ~31k, and
 * they are the ones whose absence is audible (a silent book or chapter break) rather
 * than merely slow.
 */
@Component
@ConditionalOnProperty(name = "tts.pregen.enabled", havingValue = "true")
public class TtsPregenService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TtsPregenService.class);

    private final TtsService ttsService;
    private final BibleService bibleService;
    private final ConfigurableApplicationContext applicationContext;

    @Value("${tts.pregen.dry-run:true}")
    private boolean dryRun;

    @Value("${tts.pregen.scope:all}")
    private String scope;

    @Value("${tts.pregen.threads:4}")
    private int threads;

    @Value("${tts.pregen.limit:0}")
    private int limit;

    @Value("${tts.pregen.confirm-namespace:}")
    private String confirmNamespace;

    public TtsPregenService(TtsService ttsService, BibleService bibleService,
                            ConfigurableApplicationContext applicationContext) {
        this.ttsService = ttsService;
        this.bibleService = bibleService;
        this.applicationContext = applicationContext;
    }

    /** One clip to generate: where it goes and what it says. */
    record Clip(String key, String text, String label) {}

    @Override
    public void run(ApplicationArguments args) throws Exception {
        // Credential checks are gated on live mode. A dry run prints the plan and
        // the gap count and spends nothing, so it has to work on a machine with no
        // TTS credentials at all — that is the whole point of the default mode.
        if (!dryRun && !ttsService.isEnabled()) {
            log.error("Pregen aborted: TTS is not enabled (check tts.enabled, provider, and credentials)");
            exitWith(1);
            return;
        }

        String namespace = ttsService.currentNamespace();
        if (!namespace.equals(confirmNamespace)) {
            log.error("Pregen aborted: this run would write to '{}'. Re-run with "
                            + "--tts.pregen.confirm-namespace={} if that is what you intend.",
                    namespace, namespace);
            exitWith(1);
            return;
        }

        // Pre-flight, not a per-clip discovery: an already-rotated refresh token
        // fails here, before anything is generated, instead of silently moving the
        // whole run onto the metered XAI_API_KEY. Skipped for a dry run, which
        // spends nothing and is useful for checking the plan without credentials.
        // Storage is checked with the same seriousness as credentials: without a
        // writable bucket every clip is generated, billed, and then dropped on the
        // floor. Found the hard way — three clips of quota for nothing.
        if (!dryRun && !ttsService.isSpacesReady()) {
            log.error("Pregen aborted: Spaces is not configured, so generated audio could not be "
                    + "stored. Check DO_SPACES_ACCESS_KEY / DO_SPACES_SECRET_KEY — generating "
                    + "without somewhere to put the result spends quota for nothing.");
            exitWith(1);
            return;
        }

        if (!dryRun && !ttsService.hasOAuthBearer()) {
            log.error("Pregen aborted: no SuperGrok OAuth access token. Bulk generation will not "
                    + "fall back to XAI_API_KEY (that would be real per-token spend). Mint a fresh "
                    + "token with scripts/xai_oauth_login.sh and point "
                    + "ai.xai.oauth.refresh-token-file at a writable path, then re-run.");
            exitWith(1);
            return;
        }

        log.info("Pregen namespace={} scope={} dryRun={} threads={}", namespace, scope, dryRun, threads);

        Set<String> existing = ttsService.listExistingKeys();
        log.info("Bucket holds {} objects under the audio prefix", existing.size());

        List<Clip> planned = plan();
        List<Clip> todo = new ArrayList<>(planned.size());
        for (Clip c : planned) {
            if (!existing.contains(c.key())) {
                todo.add(c);
            }
        }
        int gaps = todo.size();
        if (limit > 0 && gaps > limit) {
            todo = todo.subList(0, limit);
        }

        log.info("Planned {} clips, {} already present, {} missing, {} this run",
                planned.size(), planned.size() - gaps, gaps, todo.size());

        if (todo.isEmpty()) {
            log.info("Nothing to do — corpus is complete for this namespace");
            exitWith(0);
            return;
        }

        if (dryRun) {
            todo.stream().limit(10).forEach(c -> log.info("  would generate {}  <- \"{}\"", c.key(), c.text()));
            log.info("DRY RUN — nothing generated, nothing spent. Re-run with "
                    + "--tts.pregen.dry-run=false to generate {} clips.", todo.size());
            exitWith(0);
            return;
        }

        exitWith(generate(todo) ? 0 : 1);
    }

    /**
     * Shuts the application down. This runs inside a fully started Spring Boot web
     * app, so returning from {@link #run} would leave the server and its scheduled
     * jobs up forever — the wrapper script would never return to the shell and the
     * "one-shot command" would be unusable from any script.
     */
    private void exitWith(int code) {
        System.exit(SpringApplication.exit(applicationContext, () -> code));
    }

    /**
     * Announcements first, then verses. Chapter keys collapse across books by design,
     * so iterating every (book, chapter) pair and letting the {@link LinkedHashSet}
     * dedupe is both correct and immune to drift in the keying rule — no second copy
     * of "which chapter numbers exist" to keep in sync.
     */
    List<Clip> plan() {
        boolean wantAnnouncements = !"verses".equalsIgnoreCase(scope);
        boolean wantVerses = !"announcements".equalsIgnoreCase(scope);
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<Clip> clips = new ArrayList<>();

        if (wantAnnouncements) {
            for (Book book : bibleService.getBooks()) {
                String bookKey = ttsService.getBookKey(book.name());
                if (seen.add(bookKey)) {
                    clips.add(new Clip(bookKey, ttsService.formatBookForSpeech(book.name()), book.name()));
                }
            }
            for (Book book : bibleService.getBooks()) {
                for (ChapterInfo chapter : bibleService.getChapters(book.id())) {
                    String key = ttsService.getChapterKey(book.name(), chapter.chapter());
                    if (seen.add(key)) {
                        clips.add(new Clip(key,
                                ttsService.formatChapterForSpeech(book.name(), chapter.chapter()),
                                book.name() + " " + chapter.chapter()));
                    }
                }
            }
        }

        if (wantVerses) {
            int total = bibleService.getTotalVerses();
            for (int id = 1; id <= total; id++) {
                Verse verse = bibleService.getVerse(id).orElse(null);
                if (verse == null) continue;
                String key = ttsService.getVerseKey(id);
                if (seen.add(key)) {
                    clips.add(new Clip(key, ttsService.formatVerseForSpeech(verse), "verse " + id));
                }
            }
        }

        return clips;
    }

    /** @return true when the run finished without aborting. */
    private boolean generate(List<Clip> todo) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads));
        AtomicInteger done = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        // Every task is submitted up front, so aborting means letting the queued ones
        // return immediately rather than cancelling them — a flag drains ~31k queued
        // tasks in milliseconds.
        AtomicBoolean aborted = new AtomicBoolean();
        int total = todo.size();
        long startedAt = System.currentTimeMillis();

        for (Clip clip : todo) {
            pool.submit(() -> {
                if (aborted.get()) {
                    return;
                }
                try {
                    if (!ttsService.generateAndUpload(clip.key(), clip.text())) {
                        failed.incrementAndGet();
                        log.warn("Skipped {} ({})", clip.label(), clip.key());
                    }
                } catch (TtsService.AuthUnavailableException e) {
                    // Subscription gone: exhausted, rejected, or never present. Stop the
                    // whole run — the alternative is 31k clips on a metered key.
                    if (aborted.compareAndSet(false, true)) {
                        log.error("Pregen ABORTED at {}/{}: {}", done.get(), total, e.getMessage());
                    }
                    return;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    failed.incrementAndGet();
                    log.warn("Failed {} ({}): {}", clip.label(), clip.key(), e.getMessage());
                }
                int n = done.incrementAndGet();
                if (n % 100 == 0 || n == total) {
                    long elapsed = Math.max(1, (System.currentTimeMillis() - startedAt) / 1000);
                    log.info("Pregen {}/{} ({} failed) — {}/s, ~{} min remaining",
                            n, total, failed.get(), n / elapsed, (total - n) * elapsed / Math.max(1, n) / 60);
                }
            });
        }

        pool.shutdown();
        if (!pool.awaitTermination(24, TimeUnit.HOURS)) {
            log.warn("Pregen timed out with work outstanding — re-run to resume");
            pool.shutdownNow();
        }
        if (aborted.get()) {
            log.error("Pregen stopped early: {} generated before the abort. Nothing was billed to "
                            + "XAI_API_KEY. Restore the subscription token and re-run — the run "
                            + "resumes from the gaps.", done.get() - failed.get());
            return false;
        }
        log.info("Pregen complete: {} generated, {} failed", done.get() - failed.get(), failed.get());
        if (failed.get() > 0) {
            // A partial corpus is not success: the completeness gate will withhold the
            // voice, and a caller chaining on this needs to know to run it again.
            // Transient provider resets (GOAWAY) are the common cause and the re-run
            // is cheap, since only the gaps remain.
            log.warn("{} clips did not land — re-run to fill the gaps before the voice can be "
                    + "offered.", failed.get());
            return false;
        }
        return true;
    }
}
