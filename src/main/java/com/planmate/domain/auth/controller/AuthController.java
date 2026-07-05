package com.planmate.domain.auth.controller;

import com.planmate.domain.auth.dto.LoginRequest;
import com.planmate.domain.auth.dto.LoginResponse;
import com.planmate.domain.auth.dto.LogoutRequest;
import com.planmate.domain.auth.dto.RefreshTokenRequest;
import com.planmate.domain.auth.dto.RefreshTokenResponse;
import com.planmate.domain.auth.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        LoginResponse response = authService.login(request.email(), request.password());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestBody LogoutRequest request) {
        authService.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/token")
    public ResponseEntity<RefreshTokenResponse> token(@Valid @RequestBody RefreshTokenRequest request) {
        RefreshTokenResponse response = authService.reissue(request.refreshToken());
        return ResponseEntity.ok(response);
    }
}
