package com.readthekjv.service;

import com.readthekjv.exception.BadRequestException;
import com.readthekjv.model.dto.CollectionReadResponse;
import com.readthekjv.model.dto.CollectionResponse;
import com.readthekjv.model.dto.CollectionSummary;
import com.readthekjv.model.entity.PassageCollection;
import com.readthekjv.repository.PassageCollectionRepository;
import com.readthekjv.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
@Transactional
public class PassageCollectionService {

    private final PassageCollectionRepository collectionRepository;
    private final UserRepository userRepository;
    private final BibleService bibleService;

    public PassageCollectionService(PassageCollectionRepository collectionRepository,
                                    UserRepository userRepository,
                                    BibleService bibleService) {
        this.collectionRepository = collectionRepository;
        this.userRepository = userRepository;
        this.bibleService = bibleService;
    }

    @Transactional(readOnly = true)
    public List<CollectionSummary> list(Long userId) {
        return collectionRepository.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .map(CollectionSummary::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public CollectionResponse get(Long userId, Long id) {
        return CollectionResponse.from(findOwned(userId, id));
    }

    public CollectionResponse create(Long userId, String label, List<Integer> verseIds) {
        validateVerseIds(verseIds);
        PassageCollection c = new PassageCollection();
        c.setUser(userRepository.getReferenceById(userId));
        c.setLabel(label.trim());
        c.getVerseIds().addAll(verseIds);
        return CollectionResponse.from(saveHandlingDuplicateLabel(c));
    }

    public CollectionResponse update(Long userId, Long id, String label, List<Integer> verseIds) {
        validateVerseIds(verseIds);
        PassageCollection c = findOwned(userId, id);
        c.setLabel(label.trim());
        // Mutate the managed @OrderColumn list in place so Hibernate rewrites positions
        c.getVerseIds().clear();
        c.getVerseIds().addAll(verseIds);
        c.touch();
        return CollectionResponse.from(saveHandlingDuplicateLabel(c));
    }

    public void delete(Long userId, Long id) {
        collectionRepository.delete(findOwned(userId, id));
    }

    @Transactional(readOnly = true)
    public CollectionReadResponse getHydrated(Long userId, Long id) {
        PassageCollection c = findOwned(userId, id);
        List<CollectionReadResponse.CollectionVerse> verses = c.getVerseIds().stream()
                .map(vid -> bibleService.getVerse(vid)
                        .orElseThrow(() -> new BadRequestException("Invalid verse id: " + vid)))
                .map(CollectionReadResponse.CollectionVerse::from)
                .toList();
        return new CollectionReadResponse(c.getId(), c.getLabel(), verses);
    }

    private PassageCollection findOwned(Long userId, Long id) {
        return collectionRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Collection not found"));
    }

    private void validateVerseIds(List<Integer> verseIds) {
        int total = bibleService.getTotalVerses();
        for (Integer vid : verseIds) {
            if (vid == null || vid < 1 || vid > total) {
                throw new BadRequestException("Invalid verse id: " + vid);
            }
        }
    }

    private PassageCollection saveHandlingDuplicateLabel(PassageCollection c) {
        try {
            return collectionRepository.saveAndFlush(c);
        } catch (DataIntegrityViolationException e) {
            throw new BadRequestException("A collection with that label already exists");
        }
    }
}
