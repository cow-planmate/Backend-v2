package com.planmate.domain.auth.controller;

import com.planmate.domain.auth.dto.EmailVerificationConfirmRequest;
import com.planmate.domain.auth.dto.EmailVerificationConfirmResponse;
import com.planmate.domain.auth.dto.EmailVerificationSendRequest;
import com.planmate.domain.auth.service.EmailVerificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth/email/verification")
@RequiredArgsConstructor
public class EmailVerificationController {

    private final EmailVerificationService emailVerificationService;

    @PostMapping
    public ResponseEntity<Void> sendVerificationCode(@Valid @RequestBody EmailVerificationSendRequest request) {
        emailVerificationService.sendVerificationCode(request.email(), request.purpose());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/confirm")
    public ResponseEntity<EmailVerificationConfirmResponse> confirmVerificationCode(@Valid @RequestBody EmailVerificationConfirmRequest request) {
        EmailVerificationConfirmResponse response = emailVerificationService.confirmVerificationCode(request);
        return ResponseEntity.ok(response);
    }
}
