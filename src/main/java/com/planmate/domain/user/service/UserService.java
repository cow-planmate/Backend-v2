package com.planmate.domain.user.service;

import com.planmate.common.exception.domain.PreferredThemeNotFoundException;
import com.planmate.common.exception.domain.UserNotFoundException;
import com.planmate.domain.plan.entity.PlanMember;
import com.planmate.domain.plan.enums.PlanMemberRole;
import com.planmate.domain.plan.repository.PlanMemberRepository;
import com.planmate.domain.theme.dto.PreferredThemeDto;
import com.planmate.domain.theme.entity.PreferredTheme;
import com.planmate.domain.theme.entity.UserPreferredTheme;
import com.planmate.domain.theme.repository.PreferredThemeRepository;
import com.planmate.domain.theme.repository.UserPreferredThemeRepository;
import com.planmate.domain.user.dto.PreferredThemeListResponse;
import com.planmate.domain.user.dto.SimplePlanDto;
import com.planmate.domain.user.dto.UserProfileResponse;
import com.planmate.domain.user.entity.User;
import com.planmate.domain.user.enums.UserProvider;
import com.planmate.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PreferredThemeRepository preferredThemeRepository;
    private final UserPreferredThemeRepository userPreferredThemeRepository;
    private final PlanMemberRepository planMemberRepository;

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
                .isSocialLogin(user.getProvider() != UserProvider.LOCAL)
                .myPlans(myPlans)
                .editablePlans(editablePlans)
                .preferredThemes(preferredThemes)
                .build();
    }
}
