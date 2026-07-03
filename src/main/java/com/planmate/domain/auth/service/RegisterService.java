package com.planmate.domain.auth.service;

import com.planmate.common.exception.domain.DuplicateEmailException;
import com.planmate.common.exception.domain.DuplicateNicknameException;
import com.planmate.domain.auth.dto.NicknameVerificationResponse;
import com.planmate.domain.auth.dto.RegisterRequest;
import com.planmate.domain.auth.dto.RegisterResponse;
import com.planmate.domain.auth.enums.EmailVerificationPurpose;
import com.planmate.domain.user.entity.User;
import com.planmate.domain.user.enums.UserProvider;
import com.planmate.domain.user.enums.UserRole;
import com.planmate.domain.user.repository.UserRepository;
import com.planmate.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RegisterService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        String email = jwtTokenProvider.extractVerifiedEmail(request.signupToken(), EmailVerificationPurpose.SIGN_UP);

        if (userRepository.existsByEmail(email)) {
            throw new DuplicateEmailException();
        }
        if (userRepository.existsByNickname(request.nickname())) {
            throw new DuplicateNicknameException();
        }

        User user = User.builder()
                .email(email)
                .provider(UserProvider.LOCAL)
                .providerId(null)
                .password(passwordEncoder.encode(request.password()))
                .nickname(request.nickname())
                .gender(request.gender())
                .birthdate(request.birthdate())
                .role(UserRole.USER)
                .build();

        userRepository.save(user);

        return new RegisterResponse(user.getUserId());
    }

    @Transactional(readOnly = true)
    public NicknameVerificationResponse verifyNickname(String nickname) {
        boolean available = !userRepository.existsByNickname(nickname);
        return new NicknameVerificationResponse(available);
    }
}
