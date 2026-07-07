package com.planmate.domain.plan.controller;

import com.planmate.domain.plan.dto.CreatePlanFrameRequest;
import com.planmate.domain.plan.dto.CreatePlanFrameResponse;
import com.planmate.domain.plan.dto.CreatePlanRequest;
import com.planmate.domain.plan.dto.CreatePlanResponse;
import com.planmate.domain.plan.dto.DeleteMultiplePlansRequest;
import com.planmate.domain.plan.dto.DeleteMultiplePlansResponse;
import com.planmate.domain.plan.dto.EditPlanNameRequest;
import com.planmate.domain.plan.dto.GetCompletePlanResponse;
import com.planmate.domain.plan.dto.GetEditorsResponse;
import com.planmate.domain.plan.dto.GetPlanResponse;
import com.planmate.domain.plan.dto.GetShareStatusResponse;
import com.planmate.domain.plan.dto.UpdateShareRequest;
import com.planmate.domain.plan.service.PlanService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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

    @DeleteMapping("/{planId}")
    public ResponseEntity<Void> deletePlan(@PathVariable UUID planId) {
        planService.deletePlan(planId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    public ResponseEntity<DeleteMultiplePlansResponse> deleteMultiplePlans(
            @AuthenticationPrincipal UUID userId,
            @Valid @RequestBody DeleteMultiplePlansRequest request) {
        DeleteMultiplePlansResponse response = planService.deleteMultiplePlans(userId, request.planIds());
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{planId}/name")
    public ResponseEntity<Void> editPlanName(@PathVariable UUID planId,
                                              @Valid @RequestBody EditPlanNameRequest request) {
        planService.editPlanName(planId, request.planName());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{planId}/share")
    public ResponseEntity<GetShareStatusResponse> getShareStatus(@PathVariable UUID planId) {
        return ResponseEntity.ok(planService.getShareStatus(planId));
    }

    @PatchMapping("/{planId}/share")
    public ResponseEntity<Void> updateShare(@PathVariable UUID planId,
                                             @Valid @RequestBody UpdateShareRequest request) {
        planService.updateShare(planId, request.isShared());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{planId}/editor/me")
    public ResponseEntity<Void> resignEditorAccess(@AuthenticationPrincipal UUID userId, @PathVariable UUID planId) {
        planService.resignEditorAccess(planId, userId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{planId}/editors/{targetUserId}")
    public ResponseEntity<Void> removeEditorAccessByOwner(@PathVariable UUID planId, @PathVariable UUID targetUserId) {
        planService.removeEditorAccessByOwner(planId, targetUserId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{planId}/editors")
    public ResponseEntity<GetEditorsResponse> getEditors(@PathVariable UUID planId) {
        return ResponseEntity.ok(planService.getEditors(planId));
    }
}
