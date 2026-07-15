package com.readthekjv.service;

import com.readthekjv.model.dto.SermonNoteResponse;
import com.readthekjv.model.dto.SermonNoteSummary;
import com.readthekjv.model.entity.SermonNote;
import com.readthekjv.repository.SermonNoteRepository;
import com.readthekjv.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class SermonNoteService {

    private final SermonNoteRepository sermonNoteRepository;
    private final UserRepository userRepository;

    public SermonNoteService(SermonNoteRepository sermonNoteRepository, UserRepository userRepository) {
        this.sermonNoteRepository = sermonNoteRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<SermonNoteSummary> list(Long userId) {
        return sermonNoteRepository.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .map(SermonNoteSummary::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public SermonNoteResponse get(Long userId, UUID id) {
        return SermonNoteResponse.from(findOwned(userId, id));
    }

    public SermonNoteResponse create(Long userId, String title, String note) {
        SermonNote n = new SermonNote();
        n.setUser(userRepository.getReferenceById(userId));
        n.setTitle(title.trim());
        n.setNote(note.trim());
        return SermonNoteResponse.from(sermonNoteRepository.save(n));
    }

    public SermonNoteResponse update(Long userId, UUID id, String title, String note) {
        SermonNote n = findOwned(userId, id);
        n.setTitle(title.trim());
        n.setNote(note.trim());
        return SermonNoteResponse.from(sermonNoteRepository.save(n));
    }

    public void delete(Long userId, UUID id) {
        sermonNoteRepository.delete(findOwned(userId, id));
    }

    private SermonNote findOwned(Long userId, UUID id) {
        return sermonNoteRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sermon note not found"));
    }
}
