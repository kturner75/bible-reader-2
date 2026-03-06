package com.readthekjv.controller;

import com.readthekjv.service.XPostService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * TEMPORARY — delete after testing X posting.
 * Requires authentication (uses existing Spring Security session).
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final XPostService xPostService;

    public AdminController(XPostService xPostService) {
        this.xPostService = xPostService;
    }

    /** Manually trigger today's X post. Check server logs for result. */
    @PostMapping("/x-post-now")
    public ResponseEntity<String> postNow() {
        xPostService.postScheduled();
        return ResponseEntity.ok("X post triggered — check server logs for result");
    }
}
