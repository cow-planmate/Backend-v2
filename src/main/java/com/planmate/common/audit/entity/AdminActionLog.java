package com.planmate.common.audit.entity;

import com.planmate.common.audit.enums.AdminActionType;
import com.planmate.common.audit.enums.AdminTargetType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 관리자 액션 감사 로그. append-only라 수정자 메서드가 없다 — 정정이 필요하면 새 행을 남긴다.
 *
 * <p>{@code updated_at}이 없다는 이유로 {@code BaseTimeEntity}를 상속하지 않고 {@code createdAt}만
 * 직접 둔다 — 이 테이블엔 애초에 그 컬럼이 없다(V11 마이그레이션 참고).
 */
@Getter
@Entity
@Table(name = "admin_action_log")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class AdminActionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "log_id")
    private Long logId;

    @Column(name = "admin_user_id", nullable = false)
    private UUID adminUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 50)
    private AdminActionType actionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 30)
    private AdminTargetType targetType;

    @Column(name = "target_id", nullable = false, length = 64)
    private String targetId;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public static AdminActionLog of(UUID adminUserId, AdminActionType actionType,
                                     AdminTargetType targetType, String targetId, String reason) {
        return AdminActionLog.builder()
                .adminUserId(adminUserId)
                .actionType(actionType)
                .targetType(targetType)
                .targetId(targetId)
                .reason(reason)
                .build();
    }
}
