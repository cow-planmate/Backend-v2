package com.planmate.domain.user.service;

import com.planmate.common.exception.domain.PreferredThemeNotFoundException;
import com.planmate.common.exception.domain.UserNotFoundException;
import com.planmate.domain.plan.entity.Plan;
import com.planmate.domain.plan.entity.PlanMember;
import com.planmate.domain.plan.enums.PlanMemberRole;
import com.planmate.domain.plan.repository.PlanMemberRepository;
import com.planmate.domain.theme.entity.PreferredTheme;
import com.planmate.domain.theme.entity.UserPreferredTheme;
import com.planmate.domain.theme.enums.PreferredThemeCategory;
import com.planmate.domain.theme.repository.PreferredThemeRepository;
import com.planmate.domain.theme.repository.UserPreferredThemeRepository;
import com.planmate.domain.user.dto.PreferredThemeListResponse;
import com.planmate.domain.user.dto.UserProfileResponse;
import com.planmate.domain.user.entity.User;
import com.planmate.domain.user.enums.Gender;
import com.planmate.domain.user.enums.UserProvider;
import com.planmate.domain.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();

    @Mock
    private UserRepository userRepository;
    @Mock
    private PreferredThemeRepository preferredThemeRepository;
    @Mock
    private UserPreferredThemeRepository userPreferredThemeRepository;
    @Mock
    private PlanMemberRepository planMemberRepository;

    @InjectMocks
    private UserService userService;

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

        PreferredThemeListResponse response = userService.getPreferredThemes();

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

        userService.savePreferredThemes(USER_ID, List.of(1, 2));

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

        userService.savePreferredThemes(USER_ID, List.of(1, 1, 1));

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

        assertThatThrownBy(() -> userService.savePreferredThemes(USER_ID, List.of(1)))
                .isInstanceOf(UserNotFoundException.class);

        verify(userPreferredThemeRepository).deleteByUserUserId(USER_ID);
        verify(userPreferredThemeRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("savePreferredThemes 실패 - 요청한 ID 중 존재하지 않는 선호 테마가 있음")
    void savePreferredThemes_fail_themeNotFound() {
        PreferredTheme museum = PreferredTheme.builder().preferredThemeId(1).preferredThemeName("박물관").category(PreferredThemeCategory.ATTRACTION).build();
        given(preferredThemeRepository.findAllById(List.of(1, 999))).willReturn(List.of(museum));

        assertThatThrownBy(() -> userService.savePreferredThemes(USER_ID, List.of(1, 999)))
                .isInstanceOf(PreferredThemeNotFoundException.class);

        verify(userRepository, never()).findById(USER_ID);
        verify(userPreferredThemeRepository, never()).deleteByUserUserId(USER_ID);
        verify(userPreferredThemeRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("getProfile 성공 - LOCAL 계정, 소유/편집 플랜과 선호 테마를 모두 조합해 반환")
    void getProfile_success_localAccount() {
        User user = User.builder()
                .userId(USER_ID)
                .provider(UserProvider.LOCAL)
                .email("user@example.com")
                .nickname("planmate")
                .birthdate(LocalDate.of(1998, 5, 20))
                .gender(Gender.FEMALE)
                .build();
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

        PreferredTheme museum = PreferredTheme.builder().preferredThemeId(1).preferredThemeName("박물관").category(PreferredThemeCategory.ATTRACTION).build();
        UserPreferredTheme selection = UserPreferredTheme.builder().user(user).preferredTheme(museum).build();
        given(userPreferredThemeRepository.findByUserUserId(USER_ID)).willReturn(List.of(selection));

        Plan ownedPlan = Plan.builder().planId(UUID.randomUUID()).planName("제주도 여행").build();
        Plan editablePlan = Plan.builder().planId(UUID.randomUUID()).planName("부산 여행").build();
        PlanMember ownerMember = PlanMember.builder().plan(ownedPlan).user(user).role(PlanMemberRole.OWNER).build();
        PlanMember editorMember = PlanMember.builder().plan(editablePlan).user(user).role(PlanMemberRole.EDITOR).build();
        given(planMemberRepository.findByUserUserId(USER_ID)).willReturn(List.of(ownerMember, editorMember));

        UserProfileResponse response = userService.getProfile(USER_ID);

        assertThat(response.userId()).isEqualTo(USER_ID);
        assertThat(response.email()).isEqualTo("user@example.com");
        assertThat(response.nickname()).isEqualTo("planmate");
        assertThat(response.birthdate()).isEqualTo(LocalDate.of(1998, 5, 20));
        assertThat(response.gender()).isEqualTo("FEMALE");
        assertThat(response.isSocialLogin()).isFalse();
        assertThat(response.myPlans()).hasSize(1);
        assertThat(response.myPlans().get(0).planName()).isEqualTo("제주도 여행");
        assertThat(response.editablePlans()).hasSize(1);
        assertThat(response.editablePlans().get(0).planName()).isEqualTo("부산 여행");
        assertThat(response.preferredThemes()).hasSize(1);
        assertThat(response.preferredThemes().get(0).preferredThemeName()).isEqualTo("박물관");
    }

    @Test
    @DisplayName("getProfile 성공 - SNS 계정은 isSocialLogin=true, gender 미제공 시 null")
    void getProfile_success_socialAccount() {
        User user = User.builder()
                .userId(USER_ID)
                .provider(UserProvider.KAKAO)
                .email("kakao@example.com")
                .nickname("kakao-user")
                .birthdate(null)
                .gender(null)
                .build();
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
        given(userPreferredThemeRepository.findByUserUserId(USER_ID)).willReturn(List.of());
        given(planMemberRepository.findByUserUserId(USER_ID)).willReturn(List.of());

        UserProfileResponse response = userService.getProfile(USER_ID);

        assertThat(response.isSocialLogin()).isTrue();
        assertThat(response.gender()).isNull();
        assertThat(response.birthdate()).isNull();
        assertThat(response.myPlans()).isEmpty();
        assertThat(response.editablePlans()).isEmpty();
    }

    @Test
    @DisplayName("getProfile 실패 - userId로 유저를 찾을 수 없음")
    void getProfile_fail_userNotFound() {
        given(userRepository.findById(USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getProfile(USER_ID))
                .isInstanceOf(UserNotFoundException.class);
    }
}
