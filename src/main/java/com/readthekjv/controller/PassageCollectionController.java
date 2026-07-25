package com.readthekjv.controller;

import com.readthekjv.model.dto.CollectionReadResponse;
import com.readthekjv.model.dto.CollectionResponse;
import com.readthekjv.model.dto.CollectionSummary;
import com.readthekjv.model.dto.CreateCollectionRequest;
import com.readthekjv.model.entity.User;
import com.readthekjv.repository.UserRepository;
import com.readthekjv.service.PassageCollectionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * All endpoints require an active session — "/api/collections/**" is NOT in SecurityConfig's
 * permitAll list, so unauthenticated requests are rejected before reaching this controller.
 */
@RestController
@RequestMapping("/api/collections")
public class PassageCollectionController {

    private final PassageCollectionService collectionService;
    private final UserRepository userRepository;

    public PassageCollectionController(PassageCollectionService collectionService,
                                       UserRepository userRepository) {
        this.collectionService = collectionService;
        this.userRepository = userRepository;
    }

    private User resolveUser(UserDetails ud) {
        return userRepository.findByEmail(ud.getUsername())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
    }

    @GetMapping
    public List<CollectionSummary> list(@AuthenticationPrincipal UserDetails ud) {
        return collectionService.list(resolveUser(ud).getId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CollectionResponse create(@AuthenticationPrincipal UserDetails ud,
                                     @Valid @RequestBody CreateCollectionRequest req) {
        return collectionService.create(resolveUser(ud).getId(), req.label(), req.members());
    }

    @GetMapping("/{id}")
    public CollectionResponse get(@AuthenticationPrincipal UserDetails ud, @PathVariable Long id) {
        return collectionService.get(resolveUser(ud).getId(), id);
    }

    @PutMapping("/{id}")
    public CollectionResponse update(@AuthenticationPrincipal UserDetails ud,
                                     @PathVariable Long id,
                                     @Valid @RequestBody CreateCollectionRequest req) {
        return collectionService.update(resolveUser(ud).getId(), id, req.label(), req.members());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal UserDetails ud, @PathVariable Long id) {
        collectionService.delete(resolveUser(ud).getId(), id);
    }

    @GetMapping("/{id}/verses")
    public CollectionReadResponse getVerses(@AuthenticationPrincipal UserDetails ud, @PathVariable Long id) {
        return collectionService.getHydrated(resolveUser(ud).getId(), id);
    }
}
