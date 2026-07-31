package com.planmate.domain.user.service;

import com.planmate.common.exception.domain.DuplicateNicknameException;
import com.planmate.common.exception.domain.PreferredThemeCategoryMismatchException;
import com.planmate.common.exception.domain.ProfilePrivateException;
import com.planmate.common.exception.domain.PreferredThemeNotFoundException;
import com.planmate.common.exception.domain.UserNotFoundException;
import com.planmate.common.internal.CommunityUserCacheEvictor;
import com.planmate.common.storage.ProfileImageStorage;
import com.planmate.domain.plan.entity.PlanMember;
import com.planmate.domain.plan.enums.PlanMemberRole;
import com.planmate.domain.plan.repository.PlanMemberRepository;
import com.planmate.domain.theme.dto.PreferredThemeDto;
import com.planmate.domain.theme.entity.PreferredTheme;
import com.planmate.domain.theme.entity.UserPreferredTheme;
import com.planmate.domain.theme.enums.PreferredThemeCategory;
import com.planmate.domain.theme.repository.PreferredThemeRepository;
import com.planmate.domain.theme.repository.UserPreferredThemeRepository;
import com.planmate.domain.user.dto.PreferredThemeListResponse;
import com.planmate.domain.user.dto.ProfileImageResponse;
import com.planmate.domain.user.dto.PublicUserProfileResponse;
import com.planmate.domain.user.dto.SimplePlanDto;
import com.planmate.domain.user.dto.UserProfileResponse;
import com.planmate.domain.user.entity.User;
import com.planmate.domain.user.enums.Gender;
import com.planmate.domain.user.enums.UserProvider;
import com.planmate.domain.user.repository.UserRepository;
import com.planmate.domain.user.validator.ProfileImageValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PreferredThemeRepository preferredThemeRepository;
    private final UserPreferredThemeRepository userPreferredThemeRepository;
    private final PlanMemberRepository planMemberRepository;
    private final ProfileImageStorage profileImageStorage;
    private final ProfileImageValidator profileImageValidator;
    private final CommunityUserCacheEvictor communityUserCacheEvictor;

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

    @Transactional(readOnly = true)
    public UserProfileResponse getProfile(UUID userId) {
        User user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);

        List<PreferredThemeDto> preferredThemes = userPreferredThemeRepository.findByUserUserId(userId).stream()
                .map(userPreferredTheme -> PreferredThemeDto.from(userPreferredTheme.getPreferredTheme()))
                .toList();

        List<PlanMember> planMembers = planMemberRepository.findByUserUserId(userId);
        List<SimplePlanDto> myPlans = planMembers.stream()
                .filter(planMember -> planMember.getRole() == PlanMemberRole.OWNER)
                .map(planMember -> SimplePlanDto.from(planMember.getPlan()))
                .toList();
        List<SimplePlanDto> editablePlans = planMembers.stream()
                .filter(planMember -> planMember.getRole() == PlanMemberRole.EDITOR)
                .map(planMember -> SimplePlanDto.from(planMember.getPlan()))
                .toList();

        return UserProfileResponse.builder()
                .userId(user.getUserId())
                .email(user.getEmail())
                .nickname(user.getNickname())
                .birthdate(user.getBirthdate())
                .gender(user.getGender() == null ? null : user.getGender().name())
                .profileImageUrl(user.getProfileImageUrl())
                .isSocialLogin(user.getProvider() != UserProvider.LOCAL)
                .profilePublic(user.isProfilePublic())
                .myPlans(myPlans)
                .editablePlans(editablePlans)
                .preferredThemes(preferredThemes)
                .build();
    }

    /**
     * 타인 프로필 조회. 프로필은 기본 비공개이며, 비공개 프로필은 본인만 볼 수 있다.
     * 공개 프로필이라도 이메일·생년월일·성별은 응답에 담지 않는다.
     */
    @Transactional(readOnly = true)
    public PublicUserProfileResponse getPublicProfile(UUID viewerId, UUID targetUserId) {
        User user = userRepository.findById(targetUserId).orElseThrow(UserNotFoundException::new);

        if (!user.isProfilePublic() && !user.getUserId().equals(viewerId)) {
            throw new ProfilePrivateException();
        }

        List<PreferredThemeDto> preferredThemes = userPreferredThemeRepository.findByUserUserId(targetUserId).stream()
                .map(userPreferredTheme -> PreferredThemeDto.from(userPreferredTheme.getPreferredTheme()))
                .toList();

        List<PlanMember> planMembers = planMemberRepository.findByUserUserId(targetUserId);
        int myPlanCount = (int) planMembers.stream()
                .filter(planMember -> planMember.getRole() == PlanMemberRole.OWNER)
                .count();
        int editablePlanCount = (int) planMembers.stream()
                .filter(planMember -> planMember.getRole() == PlanMemberRole.EDITOR)
                .count();

        return PublicUserProfileResponse.builder()
                .userId(user.getUserId())
                .nickname(user.getNickname())
                .profileImageUrl(user.getProfileImageUrl())
                .preferredThemes(preferredThemes)
                .myPlanCount(myPlanCount)
                .editablePlanCount(editablePlanCount)
                .build();
    }

    /**
     * 프로필 이미지 교체. 새 파일을 먼저 올린 뒤 URL을 바꾸고, 성공한 뒤에 이전 파일을 지운다.
     * 업로드가 실패하면 기존 이미지가 그대로 남는다.
     */
    @Transactional
    public ProfileImageResponse changeProfileImage(UUID userId, MultipartFile file) {
        profileImageValidator.validate(file);

        User user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);

        String previousUrl = user.getProfileImageUrl();
        String newUrl = profileImageStorage.upload(userId, file);
        user.changeProfileImageUrl(newUrl);

        profileImageStorage.delete(previousUrl);
        communityUserCacheEvictor.evict(userId);
        return new ProfileImageResponse(newUrl);
    }

    /** 프로필 이미지 제거 — 클라이언트는 다시 Gravatar로 표시한다. */
    @Transactional
    public void deleteProfileImage(UUID userId) {
        User user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);

        String previousUrl = user.getProfileImageUrl();
        user.changeProfileImageUrl(null);
        profileImageStorage.delete(previousUrl);
        communityUserCacheEvictor.evict(userId);
    }

    @Transactional
    public void changeProfileVisibility(UUID userId, boolean profilePublic) {
        User user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);
        user.changeProfilePublic(profilePublic);
        communityUserCacheEvictor.evict(userId);
    }

    @Transactional
    public void changeNickname(UUID userId, String nickname) {
        User user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);

        // 자기 자신의 현재 닉네임과 같은 값으로 재제출하면 existsByNickname이 본인 행에 걸려
        // 오탐(중복)이 나므로, 실제로 값이 바뀌는 경우에만 중복을 확인한다.
        if (!user.getNickname().equals(nickname) && userRepository.existsByNickname(nickname)) {
            throw new DuplicateNicknameException();
        }

        user.changeNickname(nickname);
        communityUserCacheEvictor.evict(userId);
    }

    @Transactional
    public void changeBirthdate(UUID userId, LocalDate birthdate) {
        User user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);
        user.changeBirthdate(birthdate);
    }

    @Transactional
    public void changeGender(UUID userId, Gender gender) {
        User user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);
        user.changeGender(gender);
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

    @Transactional
    public void registerFcmToken(UUID userId, String fcmToken) {
        User user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);
        user.changeFcmToken(fcmToken);
    }

    @Transactional
    public void resignAccount(UUID userId) {
        User user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);
        user.softDelete();
    }
}
