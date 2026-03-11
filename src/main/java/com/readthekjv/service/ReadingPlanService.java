package com.readthekjv.service;

import com.readthekjv.model.dto.ReadingPlanDayResponse;
import com.readthekjv.model.dto.ReadingPlanResponse;
import com.readthekjv.model.entity.ReadingPlan;
import com.readthekjv.model.entity.ReadingPlanDay;
import com.readthekjv.model.entity.User;
import com.readthekjv.model.entity.UserPlanEnrollment;
import com.readthekjv.repository.ReadingPlanDayRepository;
import com.readthekjv.repository.ReadingPlanRepository;
import com.readthekjv.repository.UserPlanEnrollmentRepository;
import com.readthekjv.repository.UserRepository;
import jakarta.transaction.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
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
    private final UserRepository                  userRepo;

    public ReadingPlanService(ReadingPlanRepository planRepo,
                              ReadingPlanDayRepository dayRepo,
                              UserPlanEnrollmentRepository enrollmentRepo,
                              UserRepository userRepo) {
        this.planRepo       = planRepo;
        this.dayRepo        = dayRepo;
        this.enrollmentRepo = enrollmentRepo;
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

    private ReadingPlanResponse toResponse(ReadingPlan plan, UserPlanEnrollment enrollment) {
        boolean enrolled = (enrollment != null);
        Integer currentDay = enrolled ? enrollment.getCurrentDay() : null;

        ReadingPlanDayResponse todayDay = null;
        if (enrolled && currentDay <= plan.getTotalDays()) {
            todayDay = dayRepo.findByPlanIdAndDayNumber(plan.getId(), currentDay)
                    .map(this::toDayResponse)
                    .orElse(null);
        }

        return new ReadingPlanResponse(
                plan.getId(),
                plan.getSlug(),
                plan.getTitle(),
                plan.getTotalDays(),
                enrolled,
                currentDay,
                enrolled ? enrollment.getEnrolledAt() : null,
                todayDay
        );
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
                enrolled.add(toResponse(plan, e));
            } else {
                unenrolled.add(toResponse(plan, null));
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
            return toResponse(plan, existing);
        }

        User user = userRepo.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));

        UserPlanEnrollment enrollment = new UserPlanEnrollment();
        enrollment.setUser(user);
        enrollment.setPlan(plan);
        enrollmentRepo.save(enrollment);

        return toResponse(plan, enrollment);
    }

    /**
     * Idempotent — no-op if the user is not enrolled.
     */
    public void unenroll(Long userId, UUID planId) {
        enrollmentRepo.findByUserIdAndPlanId(userId, planId)
                .ifPresent(enrollmentRepo::delete);
    }

    /**
     * Increments currentDay. Once currentDay > totalDays, the plan is finished.
     * Further calls are idempotent (stays at totalDays + 1).
     */
    public ReadingPlanResponse completeDay(Long userId, UUID planId) {
        ReadingPlan plan = planRepo.findById(planId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Plan not found"));

        UserPlanEnrollment enrollment = enrollmentRepo.findByUserIdAndPlanId(userId, planId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not enrolled in this plan"));

        int next = enrollment.getCurrentDay() + 1;
        // Cap at totalDays + 1 so the plan stays "finished" without exceeding a sensible bound
        if (next <= plan.getTotalDays() + 1) {
            enrollment.setCurrentDay(next);
        }

        return toResponse(plan, enrollment);
    }
}
