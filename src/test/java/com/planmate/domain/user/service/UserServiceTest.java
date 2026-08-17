package com.planmate.domain.user.service;

import com.planmate.common.exception.domain.DuplicateNicknameException;
import com.planmate.common.exception.domain.UserNotFoundException;
import com.planmate.domain.plan.entity.Plan;
import com.planmate.domain.plan.entity.PlanMember;
import com.planmate.domain.plan.enums.PlanMemberRole;
import com.planmate.domain.plan.repository.PlanMemberRepository;
import com.planmate.domain.theme.entity.PreferredTheme;
import com.planmate.domain.theme.entity.UserPreferredTheme;
import com.planmate.domain.theme.enums.PreferredThemeCategory;
import com.planmate.domain.theme.repository.UserPreferredThemeRepository;
import com.planmate.domain.user.dto.UserProfileResponse;
import com.planmate.domain.user.entity.User;
import com.planmate.domain.user.enums.Gender;
import com.planmate.domain.user.enums.UserProvider;
import com.planmate.domain.user.internal.outbox.UserOutboxWriter;
import com.planmate.domain.user.repository.UserRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
    private UserPreferredThemeRepository userPreferredThemeRepository;
    @Mock
    private PlanMemberRepository planMemberRepository;
    @Mock
    private UserOutboxWriter userOutboxWriter;

    @InjectMocks
    private UserService userService;

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
        // 이 기록이 커뮤니티 복제본 갱신의 유일한 출발점이다 — 빠뜨리면 남의 피드에 옛 닉네임이
        // 영구히 남는다. 캐시 시절과 달리 수렴시켜 줄 TTL이 없고, 아무 데서도 에러가 나지 않는다.
        verify(userOutboxWriter).publish(user);
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
        verify(userOutboxWriter, never()).publish(any());
    }

    @Test
    @DisplayName("changeProfileVisibility 성공 - 공개 여부 변경도 커뮤니티에 알린다")
    void changeProfileVisibility_publishesEvent() {
        User user = mock(User.class);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

        userService.changeProfileVisibility(USER_ID, false);

        verify(user).changeProfilePublic(false);
        // 공개 설정을 껐는데 커뮤니티가 모르면 비공개 프로필이 계속 노출된다
        verify(userOutboxWriter).publish(user);
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
        // 탈퇴는 오랫동안 아무 알림도 내보내지 않아, 커뮤니티가 캐시 만료 전까지 탈퇴를 몰랐다.
        // 복제본에는 만료가 없으므로 이 기록이 빠지면 탈퇴자가 영구히 살아 있는 것처럼 보인다.
        verify(userOutboxWriter).publish(user);
    }

    @Test
    @DisplayName("resignAccount 실패 - userId로 유저를 찾을 수 없음")
    void resignAccount_fail_userNotFound() {
        given(userRepository.findById(USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> userService.resignAccount(USER_ID))
                .isInstanceOf(UserNotFoundException.class);
    }
}
