package com.planmate.domain.auth.controller;

import com.planmate.domain.auth.dto.ChangePasswordRequest;
import com.planmate.domain.auth.dto.SendTempPasswordRequest;
import com.planmate.domain.auth.dto.VerifyPasswordRequest;
import com.planmate.domain.auth.service.PasswordService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/auth/password")
@RequiredArgsConstructor
public class PasswordController {

    private final PasswordService passwordService;

    @PostMapping("/verify")
    public ResponseEntity<Void> verifyPassword(@AuthenticationPrincipal UUID userId,
                                                @Valid @RequestBody VerifyPasswordRequest request) {
        passwordService.verifyPassword(userId, request.password());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping
    public ResponseEntity<Void> changePassword(@AuthenticationPrincipal UUID userId,
                                                @Valid @RequestBody ChangePasswordRequest request) {
        passwordService.changePassword(userId, request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/email")
    public ResponseEntity<Void> sendTempPassword(@Valid @RequestBody SendTempPasswordRequest request) {
        passwordService.sendTempPassword(request.verificationToken());
        return ResponseEntity.noContent().build();
    }
}
