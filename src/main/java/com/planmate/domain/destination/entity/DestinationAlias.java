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

/**
 * 여행지의 다른 이름 — 챗봇이 채팅 문장의 지역명("경상북도", "전라남도")을 노드로 해석할 때 쓴다.
 * 접미사 제거로 만들 수 없는 이름만 둔다. 같은 별칭이 여러 노드에 걸릴 수 있다("고성").
 * planmate-ai/project_ai/22_지역_참조데이터_동기화_설계.md §5-1.
 */
@Getter
@Entity
@Table(name = "destination_alias")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class DestinationAlias {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "destination_alias_id")
    private Integer destinationAliasId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "destination_id", nullable = false)
    private Destination destination;

    @Column(nullable = false, length = 100)
    private String alias;
}
