package com.planmate.domain.user.dto;

import com.planmate.domain.theme.enums.PreferredThemeCategory;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 마이페이지 도메인 전체 점검(2026-07-04)에서 발견한 NPE(맵/리스트 안에 null이 섞이면
 * changePreferredThemes/savePreferredThemes가 500으로 죽던 문제)를 Bean Validation
 * 컨테이너 원소 제약으로 막았는지 실제로 검증한다. Spring 컨텍스트 없이 순수 Validator만 사용.
 */
class PreferredThemeRequestValidationTest {

    private static final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("changePreferredThemes - 맵 값이 null이면 검증 실패")
    void changePreferredThemes_nullMapValue_isRejected() {
        Map<PreferredThemeCategory, List<Integer>> map = new HashMap<>();
        map.put(PreferredThemeCategory.ATTRACTION, List.of(1, 2));
        map.put(PreferredThemeCategory.RESTAURANT, null);

        Set<ConstraintViolation<ChangePreferredThemesRequest>> violations =
                validator.validate(new ChangePreferredThemesRequest(map));

        assertThat(violations).isNotEmpty();
    }

    @Test
    @DisplayName("changePreferredThemes - 리스트 안에 null 원소가 섞이면 검증 실패")
    void changePreferredThemes_nullListElement_isRejected() {
        Map<PreferredThemeCategory, List<Integer>> map = new HashMap<>();
        map.put(PreferredThemeCategory.ATTRACTION, Arrays.asList(1, null));

        Set<ConstraintViolation<ChangePreferredThemesRequest>> violations =
                validator.validate(new ChangePreferredThemesRequest(map));

        assertThat(violations).isNotEmpty();
    }

    @Test
    @DisplayName("changePreferredThemes - 값이 빈 배열이면 여전히 정상(카테고리 비우기 기능 유지)")
    void changePreferredThemes_emptyListValue_isStillAllowed() {
        Map<PreferredThemeCategory, List<Integer>> map = new HashMap<>();
        map.put(PreferredThemeCategory.ACCOMMODATION, List.of());

        Set<ConstraintViolation<ChangePreferredThemesRequest>> violations =
                validator.validate(new ChangePreferredThemesRequest(map));

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("changePreferredThemes - 정상 요청은 검증 통과")
    void changePreferredThemes_normalRequest_isValid() {
        Map<PreferredThemeCategory, List<Integer>> map = new HashMap<>();
        map.put(PreferredThemeCategory.ATTRACTION, List.of(1, 2));

        Set<ConstraintViolation<ChangePreferredThemesRequest>> violations =
                validator.validate(new ChangePreferredThemesRequest(map));

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("savePreferredThemes - 리스트 안에 null 원소가 섞이면 검증 실패")
    void savePreferredThemes_nullListElement_isRejected() {
        Set<ConstraintViolation<SavePreferredThemeRequest>> violations =
                validator.validate(new SavePreferredThemeRequest(Arrays.asList(1, null)));

        assertThat(violations).isNotEmpty();
    }

    @Test
    @DisplayName("savePreferredThemes - 정상 요청은 검증 통과")
    void savePreferredThemes_normalRequest_isValid() {
        Set<ConstraintViolation<SavePreferredThemeRequest>> violations =
                validator.validate(new SavePreferredThemeRequest(List.of(1, 2)));

        assertThat(violations).isEmpty();
    }
}
