package com.planmate.domain.feedback.controller;

import com.planmate.domain.feedback.dto.AdminFeedbackListResponse;
import com.planmate.domain.feedback.service.BetaFeedbackService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/feedbacks")
@RequiredArgsConstructor
public class AdminFeedbackController {

    private final BetaFeedbackService betaFeedbackService;

    @GetMapping
    public ResponseEntity<AdminFeedbackListResponse> getFeedbacks() {
        return ResponseEntity.ok(betaFeedbackService.getFeedbacks());
    }
}
