package com.readthekjv.service;

import com.readthekjv.exception.BadRequestException;
import com.readthekjv.model.dto.CollectionReadResponse;
import com.readthekjv.model.dto.CollectionResponse;
import com.readthekjv.model.dto.CollectionSummary;
import com.readthekjv.model.entity.Passage;
import com.readthekjv.model.entity.PassageCollection;
import com.readthekjv.repository.PassageCollectionRepository;
import com.readthekjv.repository.PassageRepository;
import com.readthekjv.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class PassageCollectionService {

    private final PassageCollectionRepository collectionRepository;
    private final UserRepository userRepository;
    private final PassageRepository passageRepository;
    private final PassageService passageService;

    public PassageCollectionService(PassageCollectionRepository collectionRepository,
                                    UserRepository userRepository,
                                    PassageRepository passageRepository,
                                    PassageService passageService) {
        this.collectionRepository = collectionRepository;
        this.userRepository = userRepository;
        this.passageRepository = passageRepository;
        this.passageService = passageService;
    }

    @Transactional(readOnly = true)
    public List<CollectionSummary> list(Long userId) {
        return collectionRepository.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .map(c -> CollectionSummary.from(c, verseCount(c)))
                .toList();
    }

    @Transactional(readOnly = true)
    public CollectionResponse get(Long userId, Long id) {
        PassageCollection c = findOwned(userId, id);
        return CollectionResponse.from(c, verseCount(c));
    }

    public CollectionResponse create(Long userId, String label, List<UUID> passageIds) {
        List<UUID> resolved = validateAndResolvePassageIds(userId, passageIds);
        PassageCollection c = new PassageCollection();
        c.setUser(userRepository.getReferenceById(userId));
        c.setLabel(label.trim());
        c.getPassageIds().addAll(resolved);
        PassageCollection saved = saveHandlingDuplicateLabel(c);
        return CollectionResponse.from(saved, verseCount(saved));
    }

    public CollectionResponse update(Long userId, Long id, String label, List<UUID> passageIds) {
        List<UUID> resolved = validateAndResolvePassageIds(userId, passageIds);
        PassageCollection c = findOwned(userId, id);
        c.setLabel(label.trim());
        c.getPassageIds().clear();
        c.getPassageIds().addAll(resolved);
        c.touch();
        PassageCollection saved = saveHandlingDuplicateLabel(c);
        return CollectionResponse.from(saved, verseCount(saved));
    }

    public void delete(Long userId, Long id) {
        collectionRepository.delete(findOwned(userId, id));
    }

    @Transactional(readOnly = true)
    public CollectionReadResponse getHydrated(Long userId, Long id) {
        PassageCollection c = findOwned(userId, id);
        List<CollectionReadResponse.CollectionPassage> passages = new ArrayList<>();
        for (UUID pid : c.getPassageIds()) {
            Passage p = passageService.findReadable(userId, pid);
            List<CollectionReadResponse.CollectionVerse> verses = passageService.hydrateVerses(p);
            String reference = PassageService.formatReference(verses);
            String title = p.getTitle();
            if (title != null && title.isBlank()) title = null;
            passages.add(new CollectionReadResponse.CollectionPassage(
                    p.getId(), title, reference, p.getNaturalKey(), verses));
        }
        return new CollectionReadResponse(c.getId(), c.getLabel(), passages);
    }

    private int verseCount(PassageCollection c) {
        int total = 0;
        for (UUID pid : c.getPassageIds()) {
            Passage p = passageRepository.findById(pid).orElse(null);
            if (p != null) total += passageService.countVerses(p);
        }
        return total;
    }

    private List<UUID> validateAndResolvePassageIds(Long userId, List<UUID> passageIds) {
        if (passageIds == null || passageIds.isEmpty()) {
            throw new BadRequestException("Collection must include at least one passage");
        }
        List<UUID> resolved = new ArrayList<>(passageIds.size());
        for (UUID pid : passageIds) {
            if (pid == null) {
                throw new BadRequestException("Passage id must not be null");
            }
            // Ensures the passage exists and is readable by this user
            passageService.findReadable(userId, pid);
            resolved.add(pid);
        }
        return resolved;
    }

    private PassageCollection findOwned(Long userId, Long id) {
        return collectionRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Collection not found"));
    }

    private PassageCollection saveHandlingDuplicateLabel(PassageCollection c) {
        try {
            return collectionRepository.saveAndFlush(c);
        } catch (DataIntegrityViolationException e) {
            throw new BadRequestException("A collection with that label already exists");
        }
    }
}
