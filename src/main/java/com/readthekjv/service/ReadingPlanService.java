package com.readthekjv.service;

import com.readthekjv.model.dto.ReadingPlanDayResponse;
import com.readthekjv.model.dto.ReadingPlanResponse;
import com.readthekjv.model.entity.ReadingPlan;
import com.readthekjv.model.entity.ReadingPlanCompletion;
import com.readthekjv.model.entity.ReadingPlanDay;
import com.readthekjv.model.entity.User;
import com.readthekjv.model.entity.UserPlanEnrollment;
import com.readthekjv.repository.ReadingPlanCompletionRepository;
import com.readthekjv.repository.ReadingPlanDayRepository;
import com.readthekjv.repository.ReadingPlanRepository;
import com.readthekjv.repository.UserPlanEnrollmentRepository;
import com.readthekjv.repository.UserRepository;
import jakarta.transaction.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
public class ReadingPlanService {

    private final ReadingPlanRepository           planRepo;
    private final ReadingPlanDayRepository        dayRepo;
    private final UserPlanEnrollmentRepository    enrollmentRepo;
    private final ReadingPlanCompletionRepository completionRepo;
    private final UserRepository                  userRepo;

    public ReadingPlanService(ReadingPlanRepository planRepo,
                              ReadingPlanDayRepository dayRepo,
                              UserPlanEnrollmentRepository enrollmentRepo,
                              ReadingPlanCompletionRepository completionRepo,
                              UserRepository userRepo) {
        this.planRepo       = planRepo;
        this.dayRepo        = dayRepo;
        this.enrollmentRepo = enrollmentRepo;
        this.completionRepo = completionRepo;
        this.userRepo       = userRepo;
    }

    // ── Response Mapping ──────────────────────────────────────────────────────

    private ReadingPlanDayResponse toDayResponse(ReadingPlanDay day) {
        return new ReadingPlanDayResponse(
                day.getId(),
                day.getDayNumber(),
                day.getLabel(),
                day.getFromVerseId(),
                day.getToVerseId()
        );
    }

    private ReadingPlanResponse toResponse(ReadingPlan plan, UserPlanEnrollment enrollment, Long userId) {
        boolean enrolled = (enrollment != null);
        Integer currentDay = enrolled ? enrollment.getCurrentDay() : null;

        ReadingPlanDayResponse todayDay = null;
        if (enrolled && currentDay <= plan.getTotalDays()) {
            todayDay = dayRepo.findByPlanIdAndDayNumber(plan.getId(), currentDay)
                    .map(this::toDayResponse)
                    .orElse(null);
        }

        Integer streakDays = null;
        if (enrolled) {
            streakDays = computeStreak(userId, plan.getId());
        }

        return new ReadingPlanResponse(
                plan.getId(),
                plan.getSlug(),
                plan.getTitle(),
                plan.getTotalDays(),
                enrolled,
                currentDay,
                enrolled ? enrollment.getEnrolledAt() : null,
                todayDay,
                streakDays
        );
    }

    /**
     * Counts consecutive calendar days (going back from today or yesterday)
     * on which the user completed at least one day of this plan.
     */
    private int computeStreak(Long userId, UUID planId) {
        List<LocalDate> dates = completionRepo
                .findByUserIdAndPlanIdOrderByCompletedAtAsc(userId, planId)
                .stream()
                .map(c -> c.getCompletedAt().toLocalDate())
                .distinct()
                .sorted(Comparator.reverseOrder())
                .toList();

        if (dates.isEmpty()) return 0;

        LocalDate today     = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);

        // Streak must reach today or yesterday to be "active"
        if (!dates.get(0).equals(today) && !dates.get(0).equals(yesterday)) return 0;

        int streak = 0;
        LocalDate expected = dates.get(0);
        for (LocalDate date : dates) {
            if (date.equals(expected)) {
                streak++;
                expected = expected.minusDays(1);
            } else {
                break;
            }
        }
        return streak;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns all plans: enrolled ones first (sorted by enrolledAt ascending),
     * then unenrolled in seeder order (stable from DB).
     */
    public List<ReadingPlanResponse> getPlans(Long userId) {
        Map<UUID, UserPlanEnrollment> enrollmentMap =
                enrollmentRepo.findByUserIdWithPlan(userId).stream()
                        .collect(Collectors.toMap(e -> e.getPlan().getId(), e -> e));

        List<ReadingPlan> allPlans = planRepo.findAll();

        List<ReadingPlanResponse> enrolled   = new ArrayList<>();
        List<ReadingPlanResponse> unenrolled = new ArrayList<>();

        for (ReadingPlan plan : allPlans) {
            UserPlanEnrollment e = enrollmentMap.get(plan.getId());
            if (e != null) {
                enrolled.add(toResponse(plan, e, userId));
            } else {
                unenrolled.add(toResponse(plan, null, userId));
            }
        }

        // Enrolled first (by enrolledAt asc), then unenrolled
        enrolled.sort((a, b) -> {
            if (a.enrolledAt() == null || b.enrolledAt() == null) return 0;
            return a.enrolledAt().compareTo(b.enrolledAt());
        });

        List<ReadingPlanResponse> result = new ArrayList<>(enrolled);
        result.addAll(unenrolled);
        return result;
    }

    /**
     * Idempotent — returns the existing enrollment if already enrolled.
     */
    public ReadingPlanResponse enroll(Long userId, UUID planId) {
        ReadingPlan plan = planRepo.findById(planId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Plan not found"));

        UserPlanEnrollment existing = enrollmentRepo.findByUserIdAndPlanId(userId, planId).orElse(null);
        if (existing != null) {
            return toResponse(plan, existing, userId);
        }

        User user = userRepo.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));

        UserPlanEnrollment enrollment = new UserPlanEnrollment();
        enrollment.setUser(user);
        enrollment.setPlan(plan);
        enrollmentRepo.save(enrollment);

        return toResponse(plan, enrollment, userId);
    }

    /**
     * Idempotent — no-op if the user is not enrolled.
     */
    public void unenroll(Long userId, UUID planId) {
        enrollmentRepo.findByUserIdAndPlanId(userId, planId)
                .ifPresent(enrollmentRepo::delete);
    }

    /**
     * Increments currentDay and records a completion entry for streak tracking.
     * Idempotent: if the day was already completed, the DB unique constraint
     * prevents a duplicate; currentDay is only advanced once per day.
     */
    public ReadingPlanResponse completeDay(Long userId, UUID planId) {
        ReadingPlan plan = planRepo.findById(planId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Plan not found"));

        UserPlanEnrollment enrollment = enrollmentRepo.findByUserIdAndPlanId(userId, planId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not enrolled in this plan"));

        int dayJustCompleted = enrollment.getCurrentDay();

        // Advance enrollment pointer (cap at totalDays + 1 for "finished" state)
        int next = dayJustCompleted + 1;
        if (next <= plan.getTotalDays() + 1) {
            enrollment.setCurrentDay(next);
        }

        // Record completion for streak tracking (ignore if already exists)
        boolean alreadyRecorded = completionRepo
                .findByUserIdAndPlanIdAndDayNumber(userId, planId, dayJustCompleted)
                .isPresent();
        if (!alreadyRecorded) {
            ReadingPlanCompletion completion = new ReadingPlanCompletion();
            completion.setUserId(userId);
            completion.setPlan(plan);
            completion.setDayNumber(dayJustCompleted);
            completionRepo.save(completion);
        }

        return toResponse(plan, enrollment, userId);
    }
}
