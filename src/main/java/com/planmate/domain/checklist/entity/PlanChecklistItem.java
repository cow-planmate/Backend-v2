package com.planmate.domain.checklist.entity;

import com.planmate.common.entity.BaseTimeEntity;
import com.planmate.domain.plan.entity.Plan;
import com.sharedsync.shared.annotation.CacheEntity;
import com.sharedsync.shared.annotation.ParentId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "plan_checklist_item")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
@CacheEntity
public class PlanChecklistItem extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "checklist_item_id")
    private Long checklistItemId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false)
    @ParentId(Plan.class)
    private Plan plan;

    @Column(nullable = false, length = 255)
    private String content;

    @Column(name = "is_checked", nullable = false)
    private boolean isChecked;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    public void editContent(String content) {
        this.content = content;
    }

    public void changeChecked(boolean isChecked) {
        this.isChecked = isChecked;
    }

    public void changeSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }
}
