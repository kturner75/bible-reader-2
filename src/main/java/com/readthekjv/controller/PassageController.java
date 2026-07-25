package com.readthekjv.controller;

import com.readthekjv.model.dto.PassageDetailResponse;
import com.readthekjv.model.dto.PassageReadResponse;
import com.readthekjv.model.dto.UpsertPassageRequest;
import com.readthekjv.model.entity.User;
import com.readthekjv.repository.UserRepository;
import com.readthekjv.service.PassageService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/passages")
public class PassageController {

    private final PassageService passageService;
    private final UserRepository userRepository;

    public PassageController(PassageService passageService, UserRepository userRepository) {
        this.passageService = passageService;
        this.userRepository = userRepository;
    }

    private User resolveUser(UserDetails ud) {
        return userRepository.findByEmail(ud.getUsername())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
    }

    @GetMapping
    public List<PassageDetailResponse> list(@AuthenticationPrincipal UserDetails ud,
                                            @RequestParam(required = false) String q) {
        return passageService.listCatalog(resolveUser(ud).getId(), q);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PassageDetailResponse upsert(@AuthenticationPrincipal UserDetails ud,
                                        @Valid @RequestBody UpsertPassageRequest req) {
        return passageService.upsert(resolveUser(ud).getId(), req.naturalKey(), req.title());
    }

    @GetMapping("/{id}")
    public PassageDetailResponse get(@AuthenticationPrincipal UserDetails ud, @PathVariable UUID id) {
        return passageService.get(resolveUser(ud).getId(), id);
    }

    @PatchMapping("/{id}")
    public PassageDetailResponse updateTitle(@AuthenticationPrincipal UserDetails ud,
                                             @PathVariable UUID id,
                                             @RequestBody Map<String, String> body) {
        return passageService.updateTitle(resolveUser(ud).getId(), id, body.get("title"));
    }

    @GetMapping("/{id}/verses")
    public PassageReadResponse getVerses(@AuthenticationPrincipal UserDetails ud, @PathVariable UUID id) {
        return passageService.getHydrated(resolveUser(ud).getId(), id);
    }
}
