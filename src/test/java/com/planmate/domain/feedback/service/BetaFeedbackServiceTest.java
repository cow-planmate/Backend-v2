package com.planmate.domain.feedback.service;

import com.planmate.domain.feedback.dto.AdminFeedbackListResponse;
import com.planmate.domain.feedback.entity.BetaFeedback;
import com.planmate.domain.feedback.repository.BetaFeedbackRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BetaFeedbackServiceTest {

    @Mock
    private BetaFeedbackRepository betaFeedbackRepository;

    @InjectMocks
    private BetaFeedbackService betaFeedbackService;

    // ---------- submitFeedback (#56) ----------

    @Test
    @DisplayName("피드백 제출 성공 - content를 그대로 저장한다")
    void submitFeedback_success() {
        betaFeedbackService.submitFeedback("좋아요!");

        ArgumentCaptor<BetaFeedback> captor = ArgumentCaptor.forClass(BetaFeedback.class);
        verify(betaFeedbackRepository).save(captor.capture());
        assertThat(captor.getValue().getContent()).isEqualTo("좋아요!");
    }

    // ---------- getFeedbacks (#57) ----------

    @Test
    @DisplayName("피드백 전체 조회 성공 - 전체 목록을 그대로 반환한다")
    void getFeedbacks_success() {
        BetaFeedback feedback1 = BetaFeedback.builder()
                .feedbackId(1L)
                .content("첫 번째 피드백")
                .createdAt(LocalDateTime.of(2026, 7, 1, 10, 0))
                .build();
        BetaFeedback feedback2 = BetaFeedback.builder()
                .feedbackId(2L)
                .content("두 번째 피드백")
                .createdAt(LocalDateTime.of(2026, 7, 2, 10, 0))
                .build();
        given(betaFeedbackRepository.findAll()).willReturn(List.of(feedback1, feedback2));

        AdminFeedbackListResponse response = betaFeedbackService.getFeedbacks();

        assertThat(response.feedbacks()).hasSize(2);
        assertThat(response.feedbacks().get(0).feedbackId()).isEqualTo(1L);
        assertThat(response.feedbacks().get(0).content()).isEqualTo("첫 번째 피드백");
        assertThat(response.feedbacks().get(1).content()).isEqualTo("두 번째 피드백");
    }

    @Test
    @DisplayName("피드백 전체 조회 성공 - 데이터가 없으면 빈 리스트 반환")
    void getFeedbacks_success_empty() {
        given(betaFeedbackRepository.findAll()).willReturn(List.of());

        AdminFeedbackListResponse response = betaFeedbackService.getFeedbacks();

        assertThat(response.feedbacks()).isEmpty();
    }
}
