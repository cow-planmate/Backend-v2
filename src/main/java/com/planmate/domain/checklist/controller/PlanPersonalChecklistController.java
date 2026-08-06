package com.planmate.domain.checklist.controller;

import com.planmate.domain.checklist.dto.CreateChecklistItemRequest;
import com.planmate.domain.checklist.dto.CreateChecklistItemResponse;
import com.planmate.domain.checklist.dto.EditChecklistItemCheckRequest;
import com.planmate.domain.checklist.dto.EditChecklistItemContentRequest;
import com.planmate.domain.checklist.dto.GetChecklistResponse;
import com.planmate.domain.checklist.dto.ReorderChecklistItemsRequest;
import com.planmate.domain.checklist.service.PlanPersonalChecklistService;
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
@RequestMapping("/api/plan/{planId}/checklist/me")
@RequiredArgsConstructor
public class PlanPersonalChecklistController {

    private final PlanPersonalChecklistService planPersonalChecklistService;

    @GetMapping
    public ResponseEntity<GetChecklistResponse> getChecklist(@AuthenticationPrincipal UUID userId,
                                                               @PathVariable UUID planId) {
        return ResponseEntity.ok(planPersonalChecklistService.getChecklist(planId, userId));
    }

    @PostMapping
    public ResponseEntity<CreateChecklistItemResponse> createItem(@AuthenticationPrincipal UUID userId,
                                                                    @PathVariable UUID planId,
                                                                    @Valid @RequestBody CreateChecklistItemRequest request) {
        CreateChecklistItemResponse response =
                planPersonalChecklistService.createItem(planId, userId, request.content());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PatchMapping("/{itemId}")
    public ResponseEntity<Void> editContent(@AuthenticationPrincipal UUID userId,
                                             @PathVariable UUID planId, @PathVariable Long itemId,
                                             @Valid @RequestBody EditChecklistItemContentRequest request) {
        planPersonalChecklistService.editContent(planId, userId, itemId, request.content());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{itemId}/check")
    public ResponseEntity<Void> editChecked(@AuthenticationPrincipal UUID userId,
                                             @PathVariable UUID planId, @PathVariable Long itemId,
                                             @Valid @RequestBody EditChecklistItemCheckRequest request) {
        planPersonalChecklistService.editChecked(planId, userId, itemId, request.isChecked());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{itemId}")
    public ResponseEntity<Void> deleteItem(@AuthenticationPrincipal UUID userId,
                                            @PathVariable UUID planId, @PathVariable Long itemId) {
        planPersonalChecklistService.deleteItem(planId, userId, itemId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/order")
    public ResponseEntity<Void> reorder(@AuthenticationPrincipal UUID userId,
                                         @PathVariable UUID planId,
                                         @Valid @RequestBody ReorderChecklistItemsRequest request) {
        planPersonalChecklistService.reorder(planId, userId, request.itemIds());
        return ResponseEntity.noContent().build();
    }
}
