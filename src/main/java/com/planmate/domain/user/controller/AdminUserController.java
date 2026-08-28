package com.planmate.domain.user.controller;

import com.planmate.domain.user.dto.SuspendUserRequest;
import com.planmate.domain.user.service.UserAdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final UserAdminService userAdminService;

    @PatchMapping("/{userId}/suspend")
    public ResponseEntity<Void> suspend(
            Authentication authentication,
            @PathVariable UUID userId,
            @Valid @RequestBody SuspendUserRequest request
    ) {
        UUID adminId = UUID.fromString(authentication.getName());
        userAdminService.suspend(adminId, userId, request.until(), request.reason());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{userId}/suspend")
    public ResponseEntity<Void> unsuspend(Authentication authentication, @PathVariable UUID userId) {
        UUID adminId = UUID.fromString(authentication.getName());
        userAdminService.unsuspend(adminId, userId);
        return ResponseEntity.noContent().build();
    }
}
