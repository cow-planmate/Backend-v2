package com.planmate.domain.theme.entity;

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
@Table(name = "preferred_theme_lcls_systm_code")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class PreferredThemeLclsSystmCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "preferred_theme_lcls_systm_code_id")
    private Integer preferredThemeLclsSystmCodeId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "preferred_theme_id", nullable = false)
    private PreferredTheme preferredTheme;

    // 1=lclsSystm1, 2=lclsSystm2, 3=lclsSystm3 중 어느 파라미터에 넣을지 (테마마다 매칭 코드의 계층 깊이가 다름)
    @Column(name = "lcls_systm_level", nullable = false)
    private Integer lclsSystmLevel;

    @Column(name = "lcls_systm_cd", nullable = false, length = 10)
    private String lclsSystmCd;
}
