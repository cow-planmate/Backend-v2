package com.planmate.domain.user.internal;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 내부 사용자 조회 HTTP 엔드포인트.
 *
 * gRPC(InternalUserGrpcService)로 이관 중이며, 전환이 운영에서 검증될 때까지
 * 롤백 경로로 남겨둔다. 조회 로직은 InternalUserQueryService를 공유한다.
 */
@Hidden
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/internal")
public class InternalUserController {

    private final InternalUserQueryService queryService;

    @GetMapping("/users")
    public ResponseEntity<List<InternalUserResponse>> getUsers(@RequestParam("ids") String ids) {
        List<UUID> userIds;
        try {
            userIds = queryService.parseIds(ids);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }

        return ResponseEntity.ok(queryService.findUsers(userIds));
    }
}
