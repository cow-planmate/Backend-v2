package com.planmate.domain.collaboration.repository;

import com.planmate.domain.collaboration.entity.CollaborationRequest;
import com.planmate.domain.collaboration.enums.CollaborationRequestStatus;
import com.planmate.domain.collaboration.enums.CollaborationRequestType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface CollaborationRequestRepository extends JpaRepository<CollaborationRequest, Long> {

    // 받은 요청 목록 (status 필터 — 보통 PENDING만 조회)
    // 호출자가 이미 receiver를 알고 있으므로, 화면에 필요한 sender/plan만 즉시 로딩 (N+1 방지)
    // 활용 인덱스: idx_collab_req_receiver_status ON (receiver_id, status)
    @EntityGraph(attributePaths = {"sender", "plan"})
    List<CollaborationRequest> findByReceiverUserIdAndStatus(UUID receiverId,
                                                              CollaborationRequestStatus status);

    // 보낸 요청 목록 — 호출자가 이미 sender를 알고 있으므로 receiver/plan만 즉시 로딩
    // 활용 인덱스: idx_collab_req_sender_status ON (sender_id, status)
    @EntityGraph(attributePaths = {"receiver", "plan"})
    List<CollaborationRequest> findBySenderUserIdAndStatus(UUID senderId,
                                                            CollaborationRequestStatus status);

    // 플랜의 협업 요청 전체 조회 (플랜 상세 화면) — 호출자가 이미 planId를 알고 있으므로 sender/receiver만 즉시 로딩
    // 활용 인덱스: idx_collab_req_plan_id ON (plan_id)
    @EntityGraph(attributePaths = {"sender", "receiver"})
    List<CollaborationRequest> findByPlanPlanId(UUID planId);

    // PENDING 중복 요청 방지 (서비스 레벨 early validation)
    // DB 최종 방어: uk_collaboration_request_pending UNIQUE (sender_id, receiver_id, plan_id, type) WHERE status='PENDING'
    @Query("""
        SELECT COUNT(cr) > 0 FROM CollaborationRequest cr
        WHERE cr.sender.userId = :senderId
          AND cr.receiver.userId = :receiverId
          AND cr.plan.planId = :planId
          AND cr.type = :type
          AND cr.status = :status
        """)
    boolean existsPendingRequest(@Param("senderId") UUID senderId,
                                  @Param("receiverId") UUID receiverId,
                                  @Param("planId") UUID planId,
                                  @Param("type") CollaborationRequestType type,
                                  @Param("status") CollaborationRequestStatus status);
    // 호출: existsPendingRequest(senderId, receiverId, planId, type, CollaborationRequestStatus.PENDING)
}
