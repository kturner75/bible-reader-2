package com.readthekjv.repository;

import com.readthekjv.model.entity.VerseOfDay;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;

public interface VerseOfDayRepository extends JpaRepository<VerseOfDay, LocalDate> {
    // findById(LocalDate) inherited — covers all lookup needs
}
