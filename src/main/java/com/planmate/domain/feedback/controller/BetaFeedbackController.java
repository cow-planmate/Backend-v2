package com.planmate.domain.feedback.controller;

import com.planmate.domain.feedback.dto.BetaFeedbackRequest;
import com.planmate.domain.feedback.service.BetaFeedbackService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/beta/feedback")
@RequiredArgsConstructor
public class BetaFeedbackController {

    private final BetaFeedbackService betaFeedbackService;

    @PostMapping
    public ResponseEntity<Void> submitFeedback(@Valid @RequestBody BetaFeedbackRequest request) {
        betaFeedbackService.submitFeedback(request.content());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }
}
