package com.planmate.domain.theme.service;

import com.planmate.common.exception.domain.PreferredThemeCategoryMismatchException;
import com.planmate.common.exception.domain.PreferredThemeNotFoundException;
import com.planmate.common.exception.domain.UserNotFoundException;
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
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class UserPreferredThemeServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();

    @Mock
    private UserRepository userRepository;
    @Mock
    private PreferredThemeRepository preferredThemeRepository;
    @Mock
    private UserPreferredThemeRepository userPreferredThemeRepository;

    @InjectMocks
    private UserPreferredThemeService userPreferredThemeService;

    @Test
    @DisplayName("getPreferredThemes 성공 - 전체 테마 목록을 DTO로 변환해 반환")
    void getPreferredThemes_success() {
        PreferredTheme museum = PreferredTheme.builder()
                .preferredThemeId(1)
                .preferredThemeName("박물관")
                .category(PreferredThemeCategory.ATTRACTION)
                .build();
        PreferredTheme hotel = PreferredTheme.builder()
                .preferredThemeId(2)
                .preferredThemeName("호텔")
                .category(PreferredThemeCategory.ACCOMMODATION)
                .build();
        given(preferredThemeRepository.findAll()).willReturn(List.of(museum, hotel));

        PreferredThemeListResponse response = userPreferredThemeService.getPreferredThemes();

        assertThat(response.preferredThemes()).hasSize(2);
        assertThat(response.preferredThemes().get(0).preferredThemeId()).isEqualTo(1);
        assertThat(response.preferredThemes().get(0).category()).isEqualTo("ATTRACTION");
        assertThat(response.preferredThemes().get(1).category()).isEqualTo("ACCOMMODATION");
    }

    @Test
    @DisplayName("savePreferredThemes 성공 - 기존 선택 삭제 후 새 목록으로 교체")
    void savePreferredThemes_success() {
        User user = mock(User.class);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
        PreferredTheme museum = PreferredTheme.builder().preferredThemeId(1).preferredThemeName("박물관").category(PreferredThemeCategory.ATTRACTION).build();
        PreferredTheme hotel = PreferredTheme.builder().preferredThemeId(2).preferredThemeName("호텔").category(PreferredThemeCategory.ACCOMMODATION).build();
        given(preferredThemeRepository.findAllById(List.of(1, 2))).willReturn(List.of(museum, hotel));

        userPreferredThemeService.savePreferredThemes(USER_ID, List.of(1, 2));

        verify(userPreferredThemeRepository).deleteByUserUserId(USER_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UserPreferredTheme>> captor = ArgumentCaptor.forClass(List.class);
        verify(userPreferredThemeRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
    }

    @Test
    @DisplayName("savePreferredThemes 성공 - 중복 ID는 distinct 처리 후 하나로만 저장")
    void savePreferredThemes_success_deduplicatesRequestedIds() {
        User user = mock(User.class);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
        PreferredTheme museum = PreferredTheme.builder().preferredThemeId(1).preferredThemeName("박물관").category(PreferredThemeCategory.ATTRACTION).build();
        given(preferredThemeRepository.findAllById(List.of(1))).willReturn(List.of(museum));

        userPreferredThemeService.savePreferredThemes(USER_ID, List.of(1, 1, 1));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UserPreferredTheme>> captor = ArgumentCaptor.forClass(List.class);
        verify(userPreferredThemeRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
    }

    @Test
    @DisplayName("savePreferredThemes 실패 - userId로 유저를 찾을 수 없음 (delete는 이미 실행된 뒤 실패 - 트랜잭션 전체 롤백으로 실제 DB엔 영향 없음)")
    void savePreferredThemes_fail_userNotFound() {
        PreferredTheme museum = PreferredTheme.builder().preferredThemeId(1).preferredThemeName("박물관").category(PreferredThemeCategory.ATTRACTION).build();
        given(preferredThemeRepository.findAllById(List.of(1))).willReturn(List.of(museum));
        given(userRepository.findById(USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> userPreferredThemeService.savePreferredThemes(USER_ID, List.of(1)))
                .isInstanceOf(UserNotFoundException.class);

        verify(userPreferredThemeRepository).deleteByUserUserId(USER_ID);
        verify(userPreferredThemeRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("savePreferredThemes 실패 - 요청한 ID 중 존재하지 않는 선호 테마가 있음")
    void savePreferredThemes_fail_themeNotFound() {
        PreferredTheme museum = PreferredTheme.builder().preferredThemeId(1).preferredThemeName("박물관").category(PreferredThemeCategory.ATTRACTION).build();
        given(preferredThemeRepository.findAllById(List.of(1, 999))).willReturn(List.of(museum));

        assertThatThrownBy(() -> userPreferredThemeService.savePreferredThemes(USER_ID, List.of(1, 999)))
                .isInstanceOf(PreferredThemeNotFoundException.class);

        verify(userRepository, never()).findById(USER_ID);
        verify(userPreferredThemeRepository, never()).deleteByUserUserId(USER_ID);
        verify(userPreferredThemeRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("changePreferredThemes 성공 - 단일 카테고리 부분 수정")
    void changePreferredThemes_success_singleCategory() {
        PreferredTheme museum = PreferredTheme.builder().preferredThemeId(1).preferredThemeName("박물관").category(PreferredThemeCategory.ATTRACTION).build();
        PreferredTheme park = PreferredTheme.builder().preferredThemeId(2).preferredThemeName("공원").category(PreferredThemeCategory.ATTRACTION).build();
        given(preferredThemeRepository.findAllById(List.of(1, 2))).willReturn(List.of(museum, park));
        User user = mock(User.class);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

        userPreferredThemeService.changePreferredThemes(USER_ID, Map.of(PreferredThemeCategory.ATTRACTION, List.of(1, 2)));

        verify(userPreferredThemeRepository).deleteByUserUserIdAndPreferredThemeCategory(USER_ID, PreferredThemeCategory.ATTRACTION);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UserPreferredTheme>> captor = ArgumentCaptor.forClass(List.class);
        verify(userPreferredThemeRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
    }

    @Test
    @DisplayName("changePreferredThemes 성공 - 여러 카테고리 동시 수정, 언급 안 한 카테고리는 삭제 호출조차 안 됨")
    void changePreferredThemes_success_multipleCategories() {
        PreferredTheme museum = PreferredTheme.builder().preferredThemeId(1).preferredThemeName("박물관").category(PreferredThemeCategory.ATTRACTION).build();
        PreferredTheme cafe = PreferredTheme.builder().preferredThemeId(3).preferredThemeName("카페").category(PreferredThemeCategory.RESTAURANT).build();
        given(preferredThemeRepository.findAllById(anyList())).willReturn(List.of(museum, cafe));
        User user = mock(User.class);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

        userPreferredThemeService.changePreferredThemes(USER_ID, Map.of(
                PreferredThemeCategory.ATTRACTION, List.of(1),
                PreferredThemeCategory.RESTAURANT, List.of(3)));

        verify(userPreferredThemeRepository).deleteByUserUserIdAndPreferredThemeCategory(USER_ID, PreferredThemeCategory.ATTRACTION);
        verify(userPreferredThemeRepository).deleteByUserUserIdAndPreferredThemeCategory(USER_ID, PreferredThemeCategory.RESTAURANT);
        verify(userPreferredThemeRepository, never()).deleteByUserUserIdAndPreferredThemeCategory(USER_ID, PreferredThemeCategory.ACCOMMODATION);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UserPreferredTheme>> captor = ArgumentCaptor.forClass(List.class);
        verify(userPreferredThemeRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
    }

    @Test
    @DisplayName("changePreferredThemes 성공 - 값이 빈 배열이면 그 카테고리를 전부 비움(삭제만, 삽입 없음)")
    void changePreferredThemes_success_emptyListClearsCategory() {
        given(preferredThemeRepository.findAllById(List.of())).willReturn(List.of());
        User user = mock(User.class);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

        userPreferredThemeService.changePreferredThemes(USER_ID, Map.of(PreferredThemeCategory.ACCOMMODATION, List.of()));

        verify(userPreferredThemeRepository).deleteByUserUserIdAndPreferredThemeCategory(USER_ID, PreferredThemeCategory.ACCOMMODATION);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UserPreferredTheme>> captor = ArgumentCaptor.forClass(List.class);
        verify(userPreferredThemeRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).isEmpty();
    }

    @Test
    @DisplayName("changePreferredThemes 실패 - 요청한 테마 ID 중 존재하지 않는 게 있음")
    void changePreferredThemes_fail_themeNotFound() {
        given(preferredThemeRepository.findAllById(List.of(999))).willReturn(List.of());

        assertThatThrownBy(() -> userPreferredThemeService.changePreferredThemes(USER_ID,
                Map.of(PreferredThemeCategory.ATTRACTION, List.of(999))))
                .isInstanceOf(PreferredThemeNotFoundException.class);

        verify(userPreferredThemeRepository, never()).deleteByUserUserIdAndPreferredThemeCategory(any(), any());
    }

    @Test
    @DisplayName("changePreferredThemes 실패 - 선언한 카테고리와 실제 테마의 카테고리가 다름")
    void changePreferredThemes_fail_categoryMismatch() {
        PreferredTheme cafe = PreferredTheme.builder().preferredThemeId(5).preferredThemeName("카페").category(PreferredThemeCategory.RESTAURANT).build();
        given(preferredThemeRepository.findAllById(List.of(5))).willReturn(List.of(cafe));

        assertThatThrownBy(() -> userPreferredThemeService.changePreferredThemes(USER_ID,
                Map.of(PreferredThemeCategory.ATTRACTION, List.of(5))))
                .isInstanceOf(PreferredThemeCategoryMismatchException.class);

        verify(userPreferredThemeRepository, never()).deleteByUserUserIdAndPreferredThemeCategory(any(), any());
        verify(userRepository, never()).findById(USER_ID);
    }

    @Test
    @DisplayName("changePreferredThemes 실패 - userId로 유저를 찾을 수 없음(삭제는 이미 실행된 뒤 실패, 트랜잭션 롤백으로 실제 DB엔 영향 없음)")
    void changePreferredThemes_fail_userNotFound() {
        PreferredTheme museum = PreferredTheme.builder().preferredThemeId(1).preferredThemeName("박물관").category(PreferredThemeCategory.ATTRACTION).build();
        given(preferredThemeRepository.findAllById(List.of(1))).willReturn(List.of(museum));
        given(userRepository.findById(USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> userPreferredThemeService.changePreferredThemes(USER_ID,
                Map.of(PreferredThemeCategory.ATTRACTION, List.of(1))))
                .isInstanceOf(UserNotFoundException.class);

        verify(userPreferredThemeRepository).deleteByUserUserIdAndPreferredThemeCategory(USER_ID, PreferredThemeCategory.ATTRACTION);
        verify(userPreferredThemeRepository, never()).saveAll(anyList());
    }
}
