package com.planmate.domain.theme.entity;

import com.planmate.domain.theme.enums.PreferredThemeCategory;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import com.planmate.domain.theme.enums.converter.PreferredThemeCategoryConverter;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "preferred_theme")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class PreferredTheme {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "preferred_theme_id")
    private Integer preferredThemeId;

    @Column(name = "preferred_theme_name", nullable = false, length = 100)
    private String preferredThemeName;

    @Convert(converter = PreferredThemeCategoryConverter.class)
    @Column(nullable = false, length = 20)
    private PreferredThemeCategory category;
}
