package com.planmate.domain.user.service;

import com.planmate.common.internal.CommunityUserCacheEvictor;
import com.planmate.common.exception.domain.DuplicateNicknameException;
import com.planmate.common.exception.domain.PreferredThemeCategoryMismatchException;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
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
    @Mock
    private CommunityUserCacheEvictor communityUserCacheEvictor;

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

    @Test
    @DisplayName("changeNickname 성공 - 다른 사람이 안 쓰는 닉네임으로 변경")
    void changeNickname_success() {
        User user = mock(User.class);
        given(user.getNickname()).willReturn("oldNickname");
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
        given(userRepository.existsByNickname("newNickname")).willReturn(false);

        userService.changeNickname(USER_ID, "newNickname");

        verify(user).changeNickname("newNickname");
    }

    @Test
    @DisplayName("changeNickname 성공 - 자기 자신의 현재 닉네임으로 재제출하면 중복 검사를 건너뜀(원본 버그 수정)")
    void changeNickname_success_sameNicknameSkipsDuplicateCheck() {
        User user = mock(User.class);
        given(user.getNickname()).willReturn("sameNickname");
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

        userService.changeNickname(USER_ID, "sameNickname");

        verify(userRepository, never()).existsByNickname(anyString());
        verify(user).changeNickname("sameNickname");
    }

    @Test
    @DisplayName("changeNickname 실패 - userId로 유저를 찾을 수 없음")
    void changeNickname_fail_userNotFound() {
        given(userRepository.findById(USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> userService.changeNickname(USER_ID, "newNickname"))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    @DisplayName("changeNickname 실패 - 다른 사용자가 이미 쓰는 닉네임")
    void changeNickname_fail_duplicateNickname() {
        User user = mock(User.class);
        given(user.getNickname()).willReturn("oldNickname");
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
        given(userRepository.existsByNickname("takenNickname")).willReturn(true);

        assertThatThrownBy(() -> userService.changeNickname(USER_ID, "takenNickname"))
                .isInstanceOf(DuplicateNicknameException.class);

        verify(user, never()).changeNickname(anyString());
    }

    @Test
    @DisplayName("changeBirthdate 성공")
    void changeBirthdate_success() {
        User user = mock(User.class);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

        LocalDate newBirthdate = LocalDate.of(2000, 1, 1);
        userService.changeBirthdate(USER_ID, newBirthdate);

        verify(user).changeBirthdate(newBirthdate);
    }

    @Test
    @DisplayName("changeBirthdate 실패 - userId로 유저를 찾을 수 없음")
    void changeBirthdate_fail_userNotFound() {
        given(userRepository.findById(USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> userService.changeBirthdate(USER_ID, LocalDate.of(2000, 1, 1)))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    @DisplayName("changeGender 성공")
    void changeGender_success() {
        User user = mock(User.class);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

        userService.changeGender(USER_ID, Gender.OTHER);

        verify(user).changeGender(Gender.OTHER);
    }

    @Test
    @DisplayName("changeGender 실패 - userId로 유저를 찾을 수 없음")
    void changeGender_fail_userNotFound() {
        given(userRepository.findById(USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> userService.changeGender(USER_ID, Gender.MALE))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    @DisplayName("changePreferredThemes 성공 - 단일 카테고리 부분 수정")
    void changePreferredThemes_success_singleCategory() {
        PreferredTheme museum = PreferredTheme.builder().preferredThemeId(1).preferredThemeName("박물관").category(PreferredThemeCategory.ATTRACTION).build();
        PreferredTheme park = PreferredTheme.builder().preferredThemeId(2).preferredThemeName("공원").category(PreferredThemeCategory.ATTRACTION).build();
        given(preferredThemeRepository.findAllById(List.of(1, 2))).willReturn(List.of(museum, park));
        User user = mock(User.class);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

        userService.changePreferredThemes(USER_ID, Map.of(PreferredThemeCategory.ATTRACTION, List.of(1, 2)));

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

        userService.changePreferredThemes(USER_ID, Map.of(
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

        userService.changePreferredThemes(USER_ID, Map.of(PreferredThemeCategory.ACCOMMODATION, List.of()));

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

        assertThatThrownBy(() -> userService.changePreferredThemes(USER_ID,
                Map.of(PreferredThemeCategory.ATTRACTION, List.of(999))))
                .isInstanceOf(PreferredThemeNotFoundException.class);

        verify(userPreferredThemeRepository, never()).deleteByUserUserIdAndPreferredThemeCategory(any(), any());
    }

    @Test
    @DisplayName("changePreferredThemes 실패 - 선언한 카테고리와 실제 테마의 카테고리가 다름")
    void changePreferredThemes_fail_categoryMismatch() {
        PreferredTheme cafe = PreferredTheme.builder().preferredThemeId(5).preferredThemeName("카페").category(PreferredThemeCategory.RESTAURANT).build();
        given(preferredThemeRepository.findAllById(List.of(5))).willReturn(List.of(cafe));

        assertThatThrownBy(() -> userService.changePreferredThemes(USER_ID,
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

        assertThatThrownBy(() -> userService.changePreferredThemes(USER_ID,
                Map.of(PreferredThemeCategory.ATTRACTION, List.of(1))))
                .isInstanceOf(UserNotFoundException.class);

        verify(userPreferredThemeRepository).deleteByUserUserIdAndPreferredThemeCategory(USER_ID, PreferredThemeCategory.ATTRACTION);
        verify(userPreferredThemeRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("registerFcmToken 성공")
    void registerFcmToken_success() {
        User user = mock(User.class);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

        userService.registerFcmToken(USER_ID, "fcm-token-value");

        verify(user).changeFcmToken("fcm-token-value");
    }

    @Test
    @DisplayName("registerFcmToken 실패 - userId로 유저를 찾을 수 없음")
    void registerFcmToken_fail_userNotFound() {
        given(userRepository.findById(USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> userService.registerFcmToken(USER_ID, "fcm-token-value"))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    @DisplayName("resignAccount 성공 - 하드 삭제가 아니라 softDelete 호출")
    void resignAccount_success() {
        User user = mock(User.class);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

        userService.resignAccount(USER_ID);

        verify(user).softDelete();
        verify(userRepository, never()).deleteById(any());
        verify(userRepository, never()).delete(any());
    }

    @Test
    @DisplayName("resignAccount 실패 - userId로 유저를 찾을 수 없음")
    void resignAccount_fail_userNotFound() {
        given(userRepository.findById(USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> userService.resignAccount(USER_ID))
                .isInstanceOf(UserNotFoundException.class);
    }
}
