package com.planmate.domain.destination.entity;

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

import java.math.BigDecimal;

/**
 * 여행지 — 시도 → 시군 2단 트리(project_ai/04_기능/destination/B_계층_상세.md §4).
 * 레벨 칼럼은 없다: {@code parent}가 null이면 시도 노드, 아니면 시군 노드.
 * 이름은 부모 안에서만 유일하다(DB 제약 {@code UNIQUE NULLS NOT DISTINCT (parent_id, destination_name)}).
 */
@Getter
@Entity
@Table(name = "destination")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Destination {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "destination_id")
    private Integer destinationId;

    @Column(name = "destination_name", nullable = false, length = 100)
    private String destinationName;

    @Column(nullable = false, precision = 10, scale = 8)
    private BigDecimal latitude;

    @Column(nullable = false, precision = 11, scale = 8)
    private BigDecimal longitude;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Destination parent;

    // 1차(기본 목록) 노출 여부
    @Column(name = "is_featured", nullable = false)
    private boolean isFeatured;

    // 형제 노드 사이의 정렬 순서(§4-6)
    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    public boolean isSido() {
        return parent == null;
    }
}
