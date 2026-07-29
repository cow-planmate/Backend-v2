package com.planmate.domain.user.controller;

import com.planmate.domain.user.dto.ChangeBirthdateRequest;
import com.planmate.domain.user.dto.ChangeGenderRequest;
import com.planmate.domain.user.dto.ChangeNicknameRequest;
import com.planmate.domain.user.dto.ChangeProfileVisibilityRequest;
import com.planmate.domain.user.dto.ChangePreferredThemesRequest;
import com.planmate.domain.user.dto.PreferredThemeListResponse;
import com.planmate.domain.user.dto.PublicUserProfileResponse;
import com.planmate.domain.user.dto.RegisterFcmTokenRequest;
import com.planmate.domain.user.dto.SavePreferredThemeRequest;
import com.planmate.domain.user.dto.UserProfileResponse;
import com.planmate.domain.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    @GetMapping("/profile/{targetUserId}")
    public ResponseEntity<PublicUserProfileResponse> getPublicProfile(@AuthenticationPrincipal UUID userId,
                                                                      @PathVariable UUID targetUserId) {
        return ResponseEntity.ok(userService.getPublicProfile(userId, targetUserId));
    }

    @PatchMapping("/profile/visibility")
    public ResponseEntity<Void> changeProfileVisibility(@AuthenticationPrincipal UUID userId,
                                                         @Valid @RequestBody ChangeProfileVisibilityRequest request) {
        userService.changeProfileVisibility(userId, request.profilePublic());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/nickname")
    public ResponseEntity<Void> changeNickname(@AuthenticationPrincipal UUID userId,
                                                @Valid @RequestBody ChangeNicknameRequest request) {
        userService.changeNickname(userId, request.nickname());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/birthdate")
    public ResponseEntity<Void> changeBirthdate(@AuthenticationPrincipal UUID userId,
                                                 @Valid @RequestBody ChangeBirthdateRequest request) {
        userService.changeBirthdate(userId, request.birthdate());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/gender")
    public ResponseEntity<Void> changeGender(@AuthenticationPrincipal UUID userId,
                                              @Valid @RequestBody ChangeGenderRequest request) {
        userService.changeGender(userId, request.gender());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/preferredThemes")
    public ResponseEntity<Void> changePreferredThemes(@AuthenticationPrincipal UUID userId,
                                                       @Valid @RequestBody ChangePreferredThemesRequest request) {
        userService.changePreferredThemes(userId, request.themeUpdates());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/fcm-token")
    public ResponseEntity<Void> registerFcmToken(@AuthenticationPrincipal UUID userId,
                                                  @Valid @RequestBody RegisterFcmTokenRequest request) {
        userService.registerFcmToken(userId, request.fcmToken());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/account")
    public ResponseEntity<Void> resignAccount(@AuthenticationPrincipal UUID userId) {
        userService.resignAccount(userId);
        return ResponseEntity.noContent().build();
    }
}
