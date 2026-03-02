package com.readthekjv.service;

import com.readthekjv.exception.ConflictException;
import com.readthekjv.model.dto.RegisterRequest;
import com.readthekjv.model.dto.UserResponse;
import com.readthekjv.model.entity.User;
import com.readthekjv.repository.UserRepository;
import jakarta.transaction.Transactional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public UserResponse register(RegisterRequest req) {
        String email = req.email().toLowerCase().strip();

        if (userRepository.existsByEmail(email)) {
            throw new ConflictException("Email already registered");
        }

        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(req.password()));
        user.setDisplayName(req.displayName() != null ? req.displayName().strip() : null);

        return UserResponse.from(userRepository.save(user));
    }
}
