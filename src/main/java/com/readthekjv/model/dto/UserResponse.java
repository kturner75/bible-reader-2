package com.readthekjv.model.dto;

import com.readthekjv.model.entity.User;

import java.time.OffsetDateTime;

public record UserResponse(
    Long id,
    String email,
    String displayName,
    OffsetDateTime createdAt,
    boolean localStorageMigrated
) {
    public static UserResponse from(User user) {
        return new UserResponse(
            user.getId(),
            user.getEmail(),
            user.getDisplayName(),
            user.getCreatedAt(),
            user.isLocalStorageMigrated()
        );
    }
}
