package com.planmate.domain.plan.validator;

import com.sharedsync.shared.auth.StompAccessValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class PlanRoomAccessValidator implements StompAccessValidator {

    private final PlanAccessValidator planAccessValidator;

    @Override
    public boolean supports(String destination) {
        // 편집 room: "/topic/{planId}", "/app/{planId}"
        // presence(접속자 목록) 채널: "/topic/plan-presence/{planId}" — 편집 접근과 동일한 인가 정책 적용
        return destination != null && destination.matches(
                "^/(topic|app)/(plan-presence/)?[0-9a-fA-F-]{36}$");
    }

    @Override
    public void validate(String userId, String destination) {
        UUID planId = UUID.fromString(destination.substring(destination.lastIndexOf('/') + 1));
        planAccessValidator.validateMember(planId, UUID.fromString(userId));
    }
}
