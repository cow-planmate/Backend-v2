package com.planmate.domain.plan.controller;

import com.planmate.domain.plan.dto.CreatePlanFrameRequest;
import com.planmate.domain.plan.dto.CreatePlanFrameResponse;
import com.planmate.domain.plan.dto.CreatePlanRequest;
import com.planmate.domain.plan.dto.CreatePlanResponse;
import com.planmate.domain.plan.service.PlanService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/plan")
@RequiredArgsConstructor
public class PlanController {

    private final PlanService planService;

    @PostMapping
    public ResponseEntity<CreatePlanFrameResponse> createPlanFrame(@AuthenticationPrincipal UUID userId,
                                                                     @Valid @RequestBody CreatePlanFrameRequest request) {
        CreatePlanFrameResponse response = planService.createPlanFrame(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/full")
    public ResponseEntity<CreatePlanResponse> createPlan(@AuthenticationPrincipal UUID userId,
                                                          @Valid @RequestBody CreatePlanRequest request) {
        CreatePlanResponse response = planService.createPlan(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
