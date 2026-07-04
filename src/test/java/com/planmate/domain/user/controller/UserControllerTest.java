package com.planmate.domain.user.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planmate.domain.destination.entity.Destination;
import com.planmate.domain.destination.repository.DestinationRepository;
import com.planmate.domain.plan.entity.Plan;
import com.planmate.domain.plan.entity.PlanMember;
import com.planmate.domain.plan.enums.PlanMemberRole;
import com.planmate.domain.plan.enums.TransportationType;
import com.planmate.domain.plan.repository.PlanMemberRepository;
import com.planmate.domain.plan.repository.PlanRepository;
import com.planmate.domain.theme.entity.PreferredTheme;
import com.planmate.domain.theme.entity.UserPreferredTheme;
import com.planmate.domain.theme.repository.PreferredThemeRepository;
import com.planmate.domain.theme.repository.UserPreferredThemeRepository;
import com.planmate.domain.user.dto.SavePreferredThemeRequest;
import com.planmate.domain.user.entity.User;
import com.planmate.domain.user.enums.Gender;
import com.planmate.domain.user.enums.UserProvider;
import com.planmate.domain.user.enums.UserRole;
import com.planmate.domain.user.repository.UserRepository;
import com.planmate.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private PreferredThemeRepository preferredThemeRepository;
    @Autowired
    private UserPreferredThemeRepository userPreferredThemeRepository;
    @Autowired
    private PlanRepository planRepository;
    @Autowired
    private PlanMemberRepository planMemberRepository;
    @Autowired
    private DestinationRepository destinationRepository;

    private User testUser;
    private String accessToken;

    @BeforeEach
    void setUp() {
        // saveAndFlush 필수: savePreferredThemes가 호출하는 deleteByUserUserId(clearAutomatically=true)는
        // 이 테스트 트랜잭션 안의 미반영(flush 안 된) 변경분을 실제 INSERT 없이 그대로 날려버린다.
        // save()만 쓰면 이 User row가 실제 DB에 한 번도 기록되지 않은 채 컨텍스트에서 사라져
        // 뒤이은 조회가 UserNotFoundException을 내는 현상으로 나타난다(운영 환경에선 User가 항상
        // 이전 요청에서 이미 커밋된 상태라 재현되지 않는, 테스트 셋업 특유의 문제).
        testUser = userRepository.saveAndFlush(User.builder()
                .provider(UserProvider.LOCAL)
                .email("mypage-test-" + UUID.randomUUID() + "@example.com")
                .nickname("mypage-test-" + UUID.randomUUID())
                .birthdate(LocalDate.of(1998, 5, 20))
                .gender(Gender.FEMALE)
                .role(UserRole.USER)
                .build());
        accessToken = jwtTokenProvider.createAccessToken(testUser.getUserId(), testUser.getRole().name());
    }

    @Test
    @DisplayName("선호 테마 전체 목록 조회 성공 - 200, 시드 데이터 28개 포함")
    void getPreferredThemes_success() throws Exception {
        mockMvc.perform(get("/api/user/preferredTheme")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preferredThemes.length()").value(28))
                .andExpect(jsonPath("$.preferredThemes[?(@.preferredThemeName == '박물관')].category").value("ATTRACTION"));
    }

    @Test
    @DisplayName("선호 테마 전체 목록 조회 실패 - 인증 토큰 없으면 401")
    void getPreferredThemes_fail_noAuth() throws Exception {
        mockMvc.perform(get("/api/user/preferredTheme"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("선호 테마 초기 저장 성공 - 204, 기존 선택을 지우고 새 목록으로 반영")
    void savePreferredThemes_success() throws Exception {
        PreferredTheme museum = findThemeByName("박물관");
        PreferredTheme hotel = findThemeByName("호텔");

        mockMvc.perform(post("/api/user/preferredTheme")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SavePreferredThemeRequest(
                                List.of(museum.getPreferredThemeId(), hotel.getPreferredThemeId())))))
                .andExpect(status().isNoContent());

        assertThat(userPreferredThemeRepository.findByUserUserId(testUser.getUserId())).hasSize(2);
    }

    @Test
    @DisplayName("선호 테마 초기 저장 성공 - 두 번째 호출 시 이전 선택이 완전히 사라지고 새 목록으로 치환됨(완전 치환 검증)")
    void savePreferredThemes_success_replacesExistingSelection() throws Exception {
        PreferredTheme museum = findThemeByName("박물관");
        PreferredTheme hotel = findThemeByName("호텔");
        PreferredTheme cafe = findThemeByName("카페");

        mockMvc.perform(post("/api/user/preferredTheme")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SavePreferredThemeRequest(
                                List.of(museum.getPreferredThemeId(), hotel.getPreferredThemeId())))))
                .andExpect(status().isNoContent());
        assertThat(userPreferredThemeRepository.findByUserUserId(testUser.getUserId())).hasSize(2);

        mockMvc.perform(post("/api/user/preferredTheme")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SavePreferredThemeRequest(
                                List.of(cafe.getPreferredThemeId())))))
                .andExpect(status().isNoContent());

        List<UserPreferredTheme> remaining = userPreferredThemeRepository.findByUserUserId(testUser.getUserId());
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).getPreferredTheme().getPreferredThemeName()).isEqualTo("카페");
    }

    @Test
    @DisplayName("선호 테마 초기 저장 성공 - 요청에 중복 ID가 있어도 204, 실제 DB엔 1건만 저장(UNIQUE 제약 위반 없음)")
    void savePreferredThemes_success_duplicateIdsInRequestCollapseToOne() throws Exception {
        PreferredTheme museum = findThemeByName("박물관");

        mockMvc.perform(post("/api/user/preferredTheme")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SavePreferredThemeRequest(
                                List.of(museum.getPreferredThemeId(), museum.getPreferredThemeId())))))
                .andExpect(status().isNoContent());

        assertThat(userPreferredThemeRepository.findByUserUserId(testUser.getUserId())).hasSize(1);
    }

    @Test
    @DisplayName("선호 테마 저장 후 마이페이지 프로필 재조회 시 저장한 테마가 실제로 반영됨(엔드포인트 간 연계 검증)")
    void savePreferredThemes_then_getProfile_reflectsSavedThemes() throws Exception {
        PreferredTheme museum = findThemeByName("박물관");

        mockMvc.perform(post("/api/user/preferredTheme")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SavePreferredThemeRequest(
                                List.of(museum.getPreferredThemeId())))))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/user/profile")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preferredThemes.length()").value(1))
                .andExpect(jsonPath("$.preferredThemes[0].preferredThemeName").value("박물관"));
    }

    @Test
    @DisplayName("선호 테마 초기 저장 검증 실패 - 빈 목록이면 400, COMMON_001")
    void savePreferredThemes_validationFailure_emptyList() throws Exception {
        mockMvc.perform(post("/api/user/preferredTheme")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SavePreferredThemeRequest(List.of()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    @DisplayName("선호 테마 초기 저장 실패 - 존재하지 않는 테마 ID면 404, THEME_001")
    void savePreferredThemes_fail_themeNotFound() throws Exception {
        mockMvc.perform(post("/api/user/preferredTheme")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SavePreferredThemeRequest(List.of(999999)))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("THEME_001"));
    }

    @Test
    @DisplayName("선호 테마 초기 저장 실패 - 인증 토큰 없으면 401")
    void savePreferredThemes_fail_noAuth() throws Exception {
        PreferredTheme museum = findThemeByName("박물관");

        mockMvc.perform(post("/api/user/preferredTheme")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SavePreferredThemeRequest(List.of(museum.getPreferredThemeId())))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("마이페이지 프로필 조회 성공 - 200, 프로필/플랜/선호테마 조합 확인")
    void getProfile_success() throws Exception {
        PreferredTheme museum = findThemeByName("박물관");
        userPreferredThemeRepository.save(UserPreferredTheme.builder()
                .user(testUser)
                .preferredTheme(museum)
                .build());

        Destination destination = destinationRepository.save(Destination.builder()
                .destinationName("destination-" + UUID.randomUUID())
                .build());
        Plan ownedPlan = planRepository.save(Plan.builder()
                .planName("제주도 여행")
                .adultCount(2)
                .childCount(0)
                .transportationType(TransportationType.PUBLIC)
                .isShared(false)
                .user(testUser)
                .destination(destination)
                .build());
        planMemberRepository.save(PlanMember.builder()
                .plan(ownedPlan)
                .user(testUser)
                .role(PlanMemberRole.OWNER)
                .build());

        mockMvc.perform(get("/api/user/profile")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(testUser.getUserId().toString()))
                .andExpect(jsonPath("$.email").value(testUser.getEmail()))
                .andExpect(jsonPath("$.nickname").value(testUser.getNickname()))
                .andExpect(jsonPath("$.gender").value("FEMALE"))
                .andExpect(jsonPath("$.isSocialLogin").value(false))
                .andExpect(jsonPath("$.myPlans.length()").value(1))
                .andExpect(jsonPath("$.myPlans[0].planName").value("제주도 여행"))
                .andExpect(jsonPath("$.editablePlans.length()").value(0))
                .andExpect(jsonPath("$.preferredThemes.length()").value(1))
                .andExpect(jsonPath("$.preferredThemes[0].preferredThemeName").value("박물관"));
    }

    @Test
    @DisplayName("마이페이지 프로필 조회 성공 - 다른 사람 플랜에 EDITOR로 참여 중이면 editablePlans에 실제 DB 기준으로 반영됨")
    void getProfile_success_editablePlanFromRealEditorRole() throws Exception {
        User owner = userRepository.saveAndFlush(User.builder()
                .provider(UserProvider.LOCAL)
                .email("mypage-owner-" + UUID.randomUUID() + "@example.com")
                .nickname("mypage-owner-" + UUID.randomUUID())
                .role(UserRole.USER)
                .build());

        Destination destination = destinationRepository.save(Destination.builder()
                .destinationName("destination-" + UUID.randomUUID())
                .build());
        Plan sharedPlan = planRepository.save(Plan.builder()
                .planName("부산 여행")
                .adultCount(2)
                .childCount(0)
                .transportationType(TransportationType.PUBLIC)
                .isShared(true)
                .user(owner)
                .destination(destination)
                .build());
        planMemberRepository.save(PlanMember.builder()
                .plan(sharedPlan)
                .user(owner)
                .role(PlanMemberRole.OWNER)
                .build());
        planMemberRepository.save(PlanMember.builder()
                .plan(sharedPlan)
                .user(testUser)
                .role(PlanMemberRole.EDITOR)
                .build());

        mockMvc.perform(get("/api/user/profile")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.myPlans.length()").value(0))
                .andExpect(jsonPath("$.editablePlans.length()").value(1))
                .andExpect(jsonPath("$.editablePlans[0].planName").value("부산 여행"));
    }

    @Test
    @DisplayName("마이페이지 프로필 조회 실패 - 인증 토큰 없으면 401")
    void getProfile_fail_noAuth() throws Exception {
        mockMvc.perform(get("/api/user/profile"))
                .andExpect(status().isUnauthorized());
    }

    private PreferredTheme findThemeByName(String name) {
        return preferredThemeRepository.findAll().stream()
                .filter(theme -> theme.getPreferredThemeName().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
