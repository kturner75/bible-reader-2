package com.readthekjv.service;

import com.readthekjv.exception.BadRequestException;
import com.readthekjv.model.dto.CollectionMemberSpec;
import com.readthekjv.model.dto.CollectionReadResponse;
import com.readthekjv.model.dto.CollectionResponse;
import com.readthekjv.model.dto.CollectionSummary;
import com.readthekjv.model.dto.PassageDetailResponse;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
public class PassageCollectionService {

    /** Matches the collection builder's verse budget (and prior verse-ID cap). */
    static final int MAX_COLLECTION_VERSES = 500;

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

    public CollectionResponse create(Long userId, String label, List<CollectionMemberSpec> members) {
        List<UUID> resolved = materializeMembers(userId, members);
        PassageCollection c = new PassageCollection();
        c.setUser(userRepository.getReferenceById(userId));
        c.setLabel(label.trim());
        c.getPassageIds().addAll(resolved);
        PassageCollection saved = saveHandlingDuplicateLabel(c);
        return CollectionResponse.from(saved, verseCount(saved));
    }

    public CollectionResponse update(Long userId, Long id, String label, List<CollectionMemberSpec> members) {
        List<UUID> resolved = materializeMembers(userId, members);
        PassageCollection c = findOwned(userId, id);
        c.setLabel(label.trim());
        c.getPassageIds().clear();
        c.getPassageIds().addAll(resolved);
        c.touch();
        PassageCollection saved = saveHandlingDuplicateLabel(c);
        return CollectionResponse.from(saved, verseCount(saved));
    }

    /**
     * Resolve ordered members: existing passage ids and/or find-or-create by
     * natural key. All passage writes happen in this transaction with the
     * collection save so a failed label/validation rolls everything back.
     */
    private List<UUID> materializeMembers(Long userId, List<CollectionMemberSpec> members) {
        if (members == null || members.isEmpty()) {
            throw new BadRequestException("Collection must include at least one passage");
        }
        List<UUID> resolved = new ArrayList<>(members.size());
        int totalVerses = 0;
        for (CollectionMemberSpec m : members) {
            if (m == null) {
                throw new BadRequestException("Collection member must not be null");
            }
            boolean hasId = m.passageId() != null;
            boolean hasKey = m.naturalKey() != null && !m.naturalKey().isBlank();
            if (hasId == hasKey) {
                throw new BadRequestException("Each member needs either passageId or naturalKey");
            }

            Passage passage;
            if (hasId) {
                passage = passageService.findReadable(userId, m.passageId());
                if (Boolean.TRUE.equals(m.updateTitle())) {
                    passageService.updateTitle(userId, m.passageId(), m.title());
                    passage = passageService.findReadable(userId, m.passageId());
                }
            } else {
                // upsert with title only when updateTitle — blank draft titles use null
                // so reused passages keep their existing title (upsert semantics).
                String title = Boolean.TRUE.equals(m.updateTitle()) ? m.title() : null;
                PassageDetailResponse detail = passageService.upsert(userId, m.naturalKey().trim(), title);
                passage = passageService.findReadable(userId, detail.id());
            }

            totalVerses += passageService.countVerses(passage);
            if (totalVerses > MAX_COLLECTION_VERSES) {
                throw new BadRequestException(
                        "Collections are limited to " + MAX_COLLECTION_VERSES + " verses");
            }
            resolved.add(passage.getId());
        }
        return resolved;
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
        List<UUID> ids = c.getPassageIds();
        if (ids.isEmpty()) return 0;
        Map<UUID, Passage> byId = new HashMap<>();
        for (Passage p : passageRepository.findAllById(new HashSet<>(ids))) {
            byId.put(p.getId(), p);
        }
        int total = 0;
        for (UUID pid : ids) {
            Passage p = byId.get(pid);
            if (p != null) total += passageService.countVerses(p);
        }
        return total;
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
