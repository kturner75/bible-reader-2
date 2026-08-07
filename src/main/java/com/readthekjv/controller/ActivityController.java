package com.readthekjv.controller;

import com.readthekjv.model.entity.User;
import com.readthekjv.repository.UserRepository;
import com.readthekjv.service.ActivityService;
import com.readthekjv.service.ReadingRhythmService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.ZoneId;
import java.util.Map;

/**
 * Requires authentication — "/api/activity/**" is not in SecurityConfig's permitAll list.
 */
@RestController
@RequestMapping("/api/activity")
public class ActivityController {

    private final ActivityService  activityService;
    private final UserRepository   userRepository;

    public ActivityController(ActivityService activityService,
                              UserRepository userRepository) {
        this.activityService = activityService;
        this.userRepository  = userRepository;
    }

    private User resolveUser(UserDetails ud) {
        return userRepository.findByEmail(ud.getUsername())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
    }

    /**
     * Returns a map of ISO date → activity count for the past 365 days.
     * Only dates with at least 1 activity are included.
     *
     * Days are bucketed in the caller's zone (X-Time-Zone, IANA), matching the rest
     * of the dashboard; an absent or unparseable value falls back to the server's.
     */
    @GetMapping("/heatmap")
    public Map<String, Integer> getHeatmap(@AuthenticationPrincipal UserDetails ud,
                                           @RequestHeader(value = "X-Time-Zone", required = false) String tz) {
        ZoneId zone = ReadingRhythmService.resolveZone(tz);
        return activityService.getHeatmap(resolveUser(ud).getId(), zone);
    }
}
