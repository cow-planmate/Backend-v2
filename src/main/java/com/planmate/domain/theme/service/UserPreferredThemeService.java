package com.planmate.domain.theme.service;

import com.planmate.common.exception.domain.PreferredThemeCategoryMismatchException;
import com.planmate.common.exception.domain.PreferredThemeNotFoundException;
import com.planmate.common.exception.domain.UserNotFoundException;
import com.planmate.domain.theme.dto.PreferredThemeDto;
import com.planmate.domain.theme.entity.PreferredTheme;
import com.planmate.domain.theme.entity.UserPreferredTheme;
import com.planmate.domain.theme.enums.PreferredThemeCategory;
import com.planmate.domain.theme.repository.PreferredThemeRepository;
import com.planmate.domain.theme.repository.UserPreferredThemeRepository;
import com.planmate.domain.user.dto.PreferredThemeListResponse;
import com.planmate.domain.user.entity.User;
import com.planmate.domain.user.repository.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserPreferredThemeService {

    private final UserRepository userRepository;
    private final PreferredThemeRepository preferredThemeRepository;
    private final UserPreferredThemeRepository userPreferredThemeRepository;

    @Transactional(readOnly = true)
    public PreferredThemeListResponse getPreferredThemes() {
        List<PreferredThemeDto> preferredThemes = preferredThemeRepository.findAll().stream()
                .map(PreferredThemeDto::from)
                .toList();

        return PreferredThemeListResponse.builder()
                .preferredThemes(preferredThemes)
                .build();
    }

    @Transactional
    public void savePreferredThemes(UUID userId, List<Integer> preferredThemeIds) {
        List<Integer> distinctIds = preferredThemeIds.stream().distinct().toList();
        List<PreferredTheme> themes = preferredThemeRepository.findAllById(distinctIds);
        if (themes.size() != distinctIds.size()) {
            throw new PreferredThemeNotFoundException();
        }

        userPreferredThemeRepository.deleteByUserUserId(userId);

        // deleteByUserUserId(clearAutomatically=true)가 영속성 컨텍스트를 비우므로,
        // 삭제 이전에 조회해둔 User를 재사용하면 detached 상태라 insert 시 Hibernate가
        // "unsaved transient entity"로 오판한다(HHH000437). 삭제 이후에 다시 조회해야 한다.
        User user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);

        List<UserPreferredTheme> selections = themes.stream()
                .map(theme -> UserPreferredTheme.builder()
                        .user(user)
                        .preferredTheme(theme)
                        .build())
                .toList();
        userPreferredThemeRepository.saveAll(selections);
    }

    @Transactional
    public void changePreferredThemes(UUID userId, Map<PreferredThemeCategory, List<Integer>> themeUpdates) {
        List<Integer> allDistinctIds = themeUpdates.values().stream()
                .flatMap(List::stream)
                .distinct()
                .toList();
        List<PreferredTheme> themes = preferredThemeRepository.findAllById(allDistinctIds);
        if (themes.size() != allDistinctIds.size()) {
            throw new PreferredThemeNotFoundException();
        }
        Map<Integer, PreferredTheme> themesById = themes.stream()
                .collect(Collectors.toMap(PreferredTheme::getPreferredThemeId, theme -> theme));

        for (Map.Entry<PreferredThemeCategory, List<Integer>> entry : themeUpdates.entrySet()) {
            boolean categoryMismatch = entry.getValue().stream()
                    .map(themesById::get)
                    .anyMatch(theme -> theme.getCategory() != entry.getKey());
            if (categoryMismatch) {
                throw new PreferredThemeCategoryMismatchException();
            }
        }

        for (PreferredThemeCategory category : themeUpdates.keySet()) {
            userPreferredThemeRepository.deleteByUserUserIdAndPreferredThemeCategory(userId, category);
        }

        // 모든 삭제가 끝난 뒤에 재조회 — savePreferredThemes와 동일 이유(HHH000437 회피, A_조회.md 10-3절)
        User user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);

        List<UserPreferredTheme> selections = themeUpdates.values().stream()
                .flatMap(List::stream)
                .distinct()
                .map(themeId -> UserPreferredTheme.builder()
                        .user(user)
                        .preferredTheme(themesById.get(themeId))
                        .build())
                .toList();
        userPreferredThemeRepository.saveAll(selections);
    }
}
