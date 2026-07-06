package com.planmate.domain.plan.controller;

import com.planmate.domain.plan.dto.CreatePlanFrameRequest;
import com.planmate.domain.plan.dto.CreatePlanFrameResponse;
import com.planmate.domain.plan.dto.CreatePlanRequest;
import com.planmate.domain.plan.dto.CreatePlanResponse;
import com.planmate.domain.plan.dto.GetCompletePlanResponse;
import com.planmate.domain.plan.dto.GetPlanResponse;
import com.planmate.domain.plan.service.PlanService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    @GetMapping("/{planId}")
    public ResponseEntity<GetPlanResponse> getPlan(@PathVariable UUID planId) {
        return ResponseEntity.ok(planService.getPlan(planId));
    }

    @GetMapping("/{planId}/complete")
    public ResponseEntity<GetCompletePlanResponse> getCompletePlan(
            @AuthenticationPrincipal(errorOnInvalidType = false) UUID userId,
            @PathVariable UUID planId) {
        return ResponseEntity.ok(planService.getCompletePlan(userId, planId));
    }
}
