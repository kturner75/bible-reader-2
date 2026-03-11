package com.readthekjv.repository;

import com.readthekjv.model.entity.UserPlanEnrollment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserPlanEnrollmentRepository extends JpaRepository<UserPlanEnrollment, UUID> {

    @Query("SELECT e FROM UserPlanEnrollment e JOIN FETCH e.plan WHERE e.user.id = :userId")
    List<UserPlanEnrollment> findByUserIdWithPlan(@Param("userId") Long userId);

    Optional<UserPlanEnrollment> findByUserIdAndPlanId(Long userId, UUID planId);
}
