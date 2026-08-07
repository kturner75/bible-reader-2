package com.readthekjv.controller;

import com.readthekjv.model.dto.MarkRhythmProgressRequest;
import com.readthekjv.model.dto.RhythmLaneResponse;
import com.readthekjv.model.dto.RhythmResponse;
import com.readthekjv.model.dto.UpsertRhythmRequest;
import com.readthekjv.model.entity.User;
import com.readthekjv.repository.UserRepository;
import com.readthekjv.service.ReadingRhythmService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.ZoneId;
import java.util.List;

/**
 * All endpoints require an active session — "/api/rhythms/**" is NOT in SecurityConfig's
 * permitAll list, so unauthenticated requests are rejected before reaching this controller.
 */
@RestController
@RequestMapping("/api/rhythms")
public class ReadingRhythmController {

    private final ReadingRhythmService rhythmService;
    private final UserRepository       userRepository;

    public ReadingRhythmController(ReadingRhythmService rhythmService,
                                   UserRepository userRepository) {
        this.rhythmService  = rhythmService;
        this.userRepository = userRepository;
    }

    /**
     * The dashboard picks today's lanes with the browser's weekday, so the server's
     * day boundary must agree. Clients send their IANA zone in X-Time-Zone; an absent
     * or unparseable value falls back to the server's zone rather than failing.
     */
    private ZoneId resolveZone(String header) {
        return ReadingRhythmService.resolveZone(header);
    }

    private User resolveUser(UserDetails ud) {
        return userRepository.findByEmail(ud.getUsername())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
    }

    /** All of the user's rhythms, lanes included, with derived reading positions. */
    @GetMapping
    public List<RhythmResponse> list(@AuthenticationPrincipal UserDetails ud,
                                     @RequestHeader(value = "X-Time-Zone", required = false) String tz) {
        return rhythmService.list(resolveUser(ud).getId(), resolveZone(tz));
    }

    /**
     * Lanes scheduled for today — a suggestion for the dashboard's lead card only.
     * May be empty (nothing scheduled) or hold several; every other lane stays
     * readable and advanceable via the endpoints below.
     */
    @GetMapping("/today")
    public List<RhythmLaneResponse> today(@AuthenticationPrincipal UserDetails ud,
                                          @RequestHeader(value = "X-Time-Zone", required = false) String tz) {
        return rhythmService.todayLanes(resolveUser(ud).getId(), resolveZone(tz));
    }

    @GetMapping("/{id}")
    public RhythmResponse get(@AuthenticationPrincipal UserDetails ud,
                              @PathVariable Long id,
                              @RequestHeader(value = "X-Time-Zone", required = false) String tz) {
        return rhythmService.get(resolveUser(ud).getId(), id, resolveZone(tz));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RhythmResponse create(@AuthenticationPrincipal UserDetails ud,
                                 @RequestBody UpsertRhythmRequest req,
                                 @RequestHeader(value = "X-Time-Zone", required = false) String tz) {
        return rhythmService.create(resolveUser(ud).getId(), req.title(), req.lanes(), resolveZone(tz));
    }

    /** Replaces title + lanes. Lane cursors survive when the spec carries the lane id. */
    @PutMapping("/{id}")
    public RhythmResponse update(@AuthenticationPrincipal UserDetails ud,
                                 @PathVariable Long id,
                                 @RequestBody UpsertRhythmRequest req,
                                 @RequestHeader(value = "X-Time-Zone", required = false) String tz) {
        return rhythmService.update(resolveUser(ud).getId(), id, req.title(), req.lanes(), resolveZone(tz));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal UserDetails ud,
                       @PathVariable Long id) {
        rhythmService.delete(resolveUser(ud).getId(), id);
    }

    /** A single lane — what the reader's lane chip needs after a ?lane= deep link. */
    @GetMapping("/lanes/{laneId}")
    public RhythmLaneResponse lane(@AuthenticationPrincipal UserDetails ud,
                                   @PathVariable Long laneId,
                                   @RequestHeader(value = "X-Time-Zone", required = false) String tz) {
        return rhythmService.getLane(resolveUser(ud).getId(), laneId, resolveZone(tz));
    }

    /**
     * "I read through here." Works on any lane on any day — the lane's weekday is a
     * suggestion for what to surface, never a gate on what may be recorded.
     */
    @PostMapping("/lanes/{laneId}/progress")
    public RhythmLaneResponse markProgress(@AuthenticationPrincipal UserDetails ud,
                                           @PathVariable Long laneId,
                                           @RequestBody MarkRhythmProgressRequest req) {
        return rhythmService.markProgress(resolveUser(ud).getId(), laneId, req);
    }

    /** Clear a lane's cursor and start its book list over. */
    @PostMapping("/lanes/{laneId}/restart")
    public RhythmLaneResponse restart(@AuthenticationPrincipal UserDetails ud,
                                      @PathVariable Long laneId,
                                      @RequestHeader(value = "X-Time-Zone", required = false) String tz) {
        return rhythmService.restartLane(resolveUser(ud).getId(), laneId, resolveZone(tz));
    }
}
