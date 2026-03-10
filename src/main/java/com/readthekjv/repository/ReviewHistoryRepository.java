package com.readthekjv.repository;

import com.readthekjv.model.entity.ReviewHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReviewHistoryRepository extends JpaRepository<ReviewHistory, UUID> {

    List<ReviewHistory> findTop10ByEntryIdOrderByReviewedAtDesc(UUID entryId);
}
