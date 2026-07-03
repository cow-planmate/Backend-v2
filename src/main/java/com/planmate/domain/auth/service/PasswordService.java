package com.planmate.domain.auth.service;

import com.planmate.common.exception.domain.PasswordMismatchException;
import com.planmate.common.exception.domain.UserNotFoundException;
import com.planmate.common.mail.MailService;
import com.planmate.domain.auth.dto.ChangePasswordRequest;
import com.planmate.domain.auth.enums.EmailVerificationPurpose;
import com.planmate.domain.user.entity.User;
import com.planmate.domain.user.repository.UserRepository;
import com.planmate.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PasswordService {

    private static final String TEMP_PASSWORD_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%";
    private static final int TEMP_PASSWORD_LENGTH = 10;

    private final PasswordEncoder passwordEncoder;
    private final UserRepository userRepository;
    private final SecureRandom secureRandom;
    private final MailService mailService;
    private final JwtTokenProvider jwtTokenProvider;

    @Transactional(readOnly = true)
    public void verifyPassword(UUID userId, String password) {
        User user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);
        assertPasswordMatches(user, password);
    }

    @Transactional
    public void changePassword(UUID userId, ChangePasswordRequest request) {
        User user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);
        assertPasswordMatches(user, request.currentPassword());

        user.changePassword(passwordEncoder.encode(request.newPassword()));
    }

    @Transactional
    public void sendTempPassword(String verificationToken) {
        String email = jwtTokenProvider.extractVerifiedEmail(verificationToken, EmailVerificationPurpose.RESET_PASSWORD);
        User user = userRepository.findLocalByEmail(email).orElseThrow(UserNotFoundException::new);

        String tempPassword = generateTempPassword();
        user.changePassword(passwordEncoder.encode(tempPassword));

        mailService.sendTemporaryPasswordMail(email, tempPassword);
    }

    private void assertPasswordMatches(User user, String rawPassword) {
        if (user.getPassword() == null || !passwordEncoder.matches(rawPassword, user.getPassword())) {
            throw new PasswordMismatchException();
        }
    }

    private String generateTempPassword() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < TEMP_PASSWORD_LENGTH; i++) {
            sb.append(TEMP_PASSWORD_CHARS.charAt(secureRandom.nextInt(TEMP_PASSWORD_CHARS.length())));
        }
        return sb.toString();
    }
}
