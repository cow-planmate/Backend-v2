package com.planmate.domain.feedback.repository;

import com.planmate.domain.feedback.entity.BetaFeedback;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BetaFeedbackRepository extends JpaRepository<BetaFeedback, Long> {
    // JpaRepository.save() 기본 제공 — 저장 전용
    // 관리자 전체 조회: findAll(Pageable) 기본 제공
}
