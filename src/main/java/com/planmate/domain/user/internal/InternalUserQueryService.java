package com.planmate.domain.user.internal;

import com.planmate.common.util.GravatarHash;
import com.planmate.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 내부 서비스(커뮤니티)에 노출할 사용자 정보를 조회한다.
 *
 * HTTP(InternalUserController)와 gRPC(InternalUserGrpcService)가 이 한 곳을 공유한다 —
 * 두 프로토콜이 공존하는 동안 로직을 복사해두면 한쪽만 고쳐져 서로 다른 값을 내려주는
 * 사고로 이어진다.
 */
@Service
@RequiredArgsConstructor
public class InternalUserQueryService {

    private final UserRepository userRepository;

    /**
     * 존재하지 않는 ID는 결과에서 그냥 빠진다(예외 아님).
     * 호출부는 게시글에 저장된 닉네임 스냅샷으로 fallback 한다.
     *
     * @throws IllegalArgumentException UUID 형식이 아닌 값이 섞여 있을 때
     */
    @Transactional(readOnly = true)
    public List<InternalUserResponse> findUsers(List<UUID> userIds) {
        if (userIds.isEmpty()) {
            return List.of();
        }

        return userRepository.findAllById(userIds).stream()
                .map(u -> new InternalUserResponse(
                        u.getUserId(),
                        u.getNickname(),
                        u.isProfilePublic(),
                        u.getProfileImageUrl(),
                        GravatarHash.of(u.getEmail())))
                .toList();
    }

    /**
     * 콤마로 이어진 ID 문자열을 파싱한다. HTTP 엔드포인트가 쿼리 파라미터를 넘길 때 쓰며,
     * gRPC는 repeated 필드로 받으므로 이 경로를 타지 않는다.
     *
     * @throws IllegalArgumentException 비어 있거나 UUID 형식이 아닐 때
     */
    public List<UUID> parseIds(String ids) {
        if (ids == null || ids.isBlank()) {
            throw new IllegalArgumentException("조회할 사용자 ID가 없습니다.");
        }

        List<UUID> userIds = new ArrayList<>();
        for (String id : ids.split(",")) {
            userIds.add(UUID.fromString(id.trim()));
        }
        return userIds;
    }
}
