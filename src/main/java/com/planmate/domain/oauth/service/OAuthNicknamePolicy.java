package com.planmate.domain.oauth.service;

import com.planmate.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** SNS 프로필에서 받은 닉네임을 정제하고, 중복이면 번호를 붙여 유일하게 만든다. */
@Component
@RequiredArgsConstructor
public class OAuthNicknamePolicy {

    private final UserRepository userRepository;

    public String sanitize(String nickname) {
        if (nickname == null || nickname.isBlank()) {
            return "user";
        }

        String cleaned = nickname.trim();
        cleaned = cleaned.replaceAll("\\s+", "");
        cleaned = cleaned.replaceAll("[\\p{So}\\p{Cn}]", "");
        cleaned = cleaned.replaceAll("[^a-zA-Z0-9가-힣]", "");

        if (cleaned.isBlank()) {
            cleaned = "user";
        }

        if (cleaned.length() > 20) {
            cleaned = cleaned.substring(0, 20);
        }

        return cleaned;
    }

    public String resolveUnique(String baseNickname) {
        if (!userRepository.existsByNickname(baseNickname)) {
            return baseNickname;
        }

        int index = 1;
        String candidate;
        do {
            candidate = baseNickname + index;
            index++;
        } while (userRepository.existsByNickname(candidate));

        return candidate;
    }
}
