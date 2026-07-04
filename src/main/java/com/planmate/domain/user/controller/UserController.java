package com.planmate.domain.user.controller;

import com.planmate.domain.user.dto.PreferredThemeListResponse;
import com.planmate.domain.user.dto.SavePreferredThemeRequest;
import com.planmate.domain.user.dto.UserProfileResponse;
import com.planmate.domain.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/preferredTheme")
    public ResponseEntity<PreferredThemeListResponse> getPreferredThemes() {
        return ResponseEntity.ok(userService.getPreferredThemes());
    }

    @PostMapping("/preferredTheme")
    public ResponseEntity<Void> savePreferredThemes(@AuthenticationPrincipal UUID userId,
                                                     @Valid @RequestBody SavePreferredThemeRequest request) {
        userService.savePreferredThemes(userId, request.preferredThemeIds());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/profile")
    public ResponseEntity<UserProfileResponse> getProfile(@AuthenticationPrincipal UUID userId) {
        return ResponseEntity.ok(userService.getProfile(userId));
    }
}
