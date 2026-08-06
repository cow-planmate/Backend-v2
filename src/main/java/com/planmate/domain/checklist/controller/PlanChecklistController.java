package com.planmate.domain.checklist.controller;

import com.planmate.domain.checklist.dto.CreateChecklistItemRequest;
import com.planmate.domain.checklist.dto.CreateChecklistItemResponse;
import com.planmate.domain.checklist.dto.EditChecklistItemCheckRequest;
import com.planmate.domain.checklist.dto.EditChecklistItemContentRequest;
import com.planmate.domain.checklist.dto.GetChecklistResponse;
import com.planmate.domain.checklist.dto.ReorderChecklistItemsRequest;
import com.planmate.domain.checklist.service.PlanChecklistService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
@RequestMapping("/api/plan/{planId}/checklist")
@RequiredArgsConstructor
public class PlanChecklistController {

    private final PlanChecklistService planChecklistService;

    @GetMapping
    public ResponseEntity<GetChecklistResponse> getChecklist(@PathVariable UUID planId) {
        return ResponseEntity.ok(planChecklistService.getChecklist(planId));
    }

    @PostMapping
    public ResponseEntity<CreateChecklistItemResponse> createItem(@PathVariable UUID planId,
                                                                    @Valid @RequestBody CreateChecklistItemRequest request) {
        CreateChecklistItemResponse response = planChecklistService.createItem(planId, request.content());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PatchMapping("/{itemId}")
    public ResponseEntity<Void> editContent(@PathVariable UUID planId, @PathVariable Long itemId,
                                             @Valid @RequestBody EditChecklistItemContentRequest request) {
        planChecklistService.editContent(planId, itemId, request.content());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{itemId}/check")
    public ResponseEntity<Void> editChecked(@PathVariable UUID planId, @PathVariable Long itemId,
                                             @Valid @RequestBody EditChecklistItemCheckRequest request) {
        planChecklistService.editChecked(planId, itemId, request.isChecked());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{itemId}")
    public ResponseEntity<Void> deleteItem(@PathVariable UUID planId, @PathVariable Long itemId) {
        planChecklistService.deleteItem(planId, itemId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/order")
    public ResponseEntity<Void> reorder(@PathVariable UUID planId,
                                         @Valid @RequestBody ReorderChecklistItemsRequest request) {
        planChecklistService.reorder(planId, request.itemIds());
        return ResponseEntity.noContent().build();
    }
}
