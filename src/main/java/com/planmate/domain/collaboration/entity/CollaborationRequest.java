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
}
