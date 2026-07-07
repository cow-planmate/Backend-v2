package com.planmate.domain.collaboration.entity;

import com.planmate.common.entity.BaseTimeEntity;
import com.planmate.domain.collaboration.enums.CollaborationRequestStatus;
import com.planmate.domain.collaboration.enums.CollaborationRequestType;
import com.planmate.domain.plan.entity.Plan;
import com.planmate.domain.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "collaboration_request")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class CollaborationRequest extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "collaboration_request_id")
    private Long collaborationRequestId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private CollaborationRequestType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private CollaborationRequestStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_id", nullable = false)
    private User sender;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receiver_id", nullable = false)
    private User receiver;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false)
    private Plan plan;

    public void accept() {
        this.status = CollaborationRequestStatus.ACCEPTED;
    }

    public void reject() {
        this.status = CollaborationRequestStatus.REJECTED;
    }

    // 수락 시 새로 편집자가 되는 사람 — INVITE는 초대받은 사람(receiver), REQUEST는 요청을 보낸 사람(sender)
    public User getTargetUserForAcceptance() {
        return switch (this.type) {
            case INVITE -> this.receiver;
            case REQUEST -> this.sender;
        };
    }

    // plan_member.invited_by에 들어갈, 이 멤버십을 승인한 기존 멤버 — getTargetUserForAcceptance()와 반대 방향
    // (데이터베이스_설계_v2.md 1.11절: INVITE 수락은 sender_id, REQUEST 수락은 receiver_id)
    public User getApprover() {
        return switch (this.type) {
            case INVITE -> this.sender;
            case REQUEST -> this.receiver;
        };
    }
}
