package com.readthekjv.controller;

import com.readthekjv.model.entity.VerseOfDay;
import com.readthekjv.service.VerseOfDayService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Public endpoint that serves today's AI-generated verse.
 * Returns 404 when no verse has been generated yet (e.g. missing API key on local dev),
 * so the landing page falls back to its curated client-side list.
 */
@RestController
@RequestMapping("/api/verse-of-day")
public class VerseOfDayController {

    private final VerseOfDayService verseOfDayService;

    public VerseOfDayController(VerseOfDayService verseOfDayService) {
        this.verseOfDayService = verseOfDayService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getTodaysVerse() {
        Optional<VerseOfDay> votd = verseOfDayService.getTodaysVerse();
        if (votd.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        VerseOfDay v = votd.get();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("verseId", v.getVerseId());
        body.put("blurb",   v.getAiBlurb());
        body.put("date",    v.getDate().toString());
        return ResponseEntity.ok(body);
    }
}
