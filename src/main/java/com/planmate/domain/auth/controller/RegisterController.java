package com.planmate.domain.auth.controller;

import com.planmate.domain.auth.dto.NicknameVerificationRequest;
import com.planmate.domain.auth.dto.NicknameVerificationResponse;
import com.planmate.domain.auth.dto.RegisterRequest;
import com.planmate.domain.auth.dto.RegisterResponse;
import com.planmate.domain.auth.service.RegisterService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth/register")
@RequiredArgsConstructor
public class RegisterController {

    private final RegisterService registerService;

    @PostMapping
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        RegisterResponse response = registerService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/nickname/verify")
    public ResponseEntity<NicknameVerificationResponse> verifyNickname(@Valid @RequestBody NicknameVerificationRequest request) {
        NicknameVerificationResponse response = registerService.verifyNickname(request.nickname());
        return ResponseEntity.ok(response);
    }
}
