package com.planmate.domain.feedback.service;

import com.planmate.domain.feedback.dto.AdminFeedbackListResponse;
import com.planmate.domain.feedback.dto.BetaFeedbackDto;
import com.planmate.domain.feedback.entity.BetaFeedback;
import com.planmate.domain.feedback.repository.BetaFeedbackRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BetaFeedbackService {

    private final BetaFeedbackRepository betaFeedbackRepository;

    @Transactional
    public void submitFeedback(String content) {
        betaFeedbackRepository.save(BetaFeedback.builder()
                .content(content)
                .build());
    }

    @Transactional(readOnly = true)
    public AdminFeedbackListResponse getFeedbacks() {
        return AdminFeedbackListResponse.builder()
                .feedbacks(betaFeedbackRepository.findAll().stream()
                        .map(BetaFeedbackDto::from)
                        .toList())
                .build();
    }
}
