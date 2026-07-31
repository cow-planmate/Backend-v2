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
import com.planmate.domain.theme.enums.PreferredThemeCategory;
import com.planmate.domain.theme.repository.PreferredThemeRepository;
import com.planmate.domain.theme.repository.UserPreferredThemeRepository;
import com.planmate.domain.user.dto.ChangeBirthdateRequest;
import com.planmate.domain.user.dto.ChangeGenderRequest;
import com.planmate.domain.user.dto.ChangeNicknameRequest;
import com.planmate.domain.user.dto.ChangePreferredThemesRequest;
import com.planmate.domain.user.dto.ChangeProfileVisibilityRequest;
import com.planmate.domain.user.dto.RegisterFcmTokenRequest;
import com.planmate.domain.user.dto.SavePreferredThemeRequest;
import com.planmate.common.storage.ProfileImageStorage;
import com.planmate.domain.user.entity.User;
import com.planmate.domain.user.enums.Gender;
import com.planmate.domain.user.enums.UserProvider;
import com.planmate.domain.user.enums.UserRole;
import com.planmate.domain.user.repository.UserRepository;
import com.planmate.security.JwtTokenProvider;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private EntityManager entityManager;

    // 실제 오브젝트 스토리지에 붙지 않는다 — 검증 대상은 업로드 결과 URL이 DB·응답에
    // 반영되는 배선이고, MinIO 왕복 자체는 자격증명/네트워크에 의존해 테스트가 취약해진다.
    @MockitoBean
    private ProfileImageStorage profileImageStorage;

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
                .nickname("mypage-" + UUID.randomUUID().toString().substring(0, 8))
                .birthdate(LocalDate.of(1998, 5, 20))
                .gender(Gender.FEMALE)
                .role(UserRole.USER)
                .build());
        accessToken = jwtTokenProvider.createAccessToken(testUser.getUserId(), testUser.getRole().name());

        // 실제 ProfileImageStorage가 만드는 것과 같은 형태(사용자별 경로 + 매번 새 UUID)를 흉내낸다
        when(profileImageStorage.upload(any(UUID.class), any()))
                .thenAnswer(invocation -> "http://localhost:9000/planmate/profile/"
                        + invocation.getArgument(0) + "/" + UUID.randomUUID() + ".png");
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
                .latitude(new BigDecimal("37.56330000"))
                .longitude(new BigDecimal("126.97970000"))
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
                .latitude(new BigDecimal("37.56330000"))
                .longitude(new BigDecimal("126.97970000"))
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
    @DisplayName("마이페이지 프로필 조회 성공 - SNS 가입 계정은 isSocialLogin이 실제 DB 기준으로 true(LOCAL 분기만 검증돼있던 것 보강)")
    void getProfile_success_socialAccountRealDb() throws Exception {
        User socialUser = userRepository.saveAndFlush(User.builder()
                .provider(UserProvider.KAKAO)
                .providerId("kakao-" + UUID.randomUUID())
                .email("mypage-social-" + UUID.randomUUID() + "@example.com")
                .nickname("mypage-social-" + UUID.randomUUID().toString().substring(0, 8))
                .role(UserRole.USER)
                .build());
        String socialAccessToken = jwtTokenProvider.createAccessToken(socialUser.getUserId(), socialUser.getRole().name());

        mockMvc.perform(get("/api/user/profile")
                        .header("Authorization", "Bearer " + socialAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSocialLogin").value(true));
    }

    @Test
    @DisplayName("마이페이지 프로필 조회 실패 - 인증 토큰 없으면 401")
    void getProfile_fail_noAuth() throws Exception {
        mockMvc.perform(get("/api/user/profile"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("타인 프로필 조회 성공 - 200, 공개 프로필도 닉네임/일정 개수만 주고 이메일·생년월일·성별은 응답에 없음")
    void getPublicProfile_success() throws Exception {
        User other = createOtherUser(true);
        Plan ownedPlan = createPlanOwnedBy(other);
        planMemberRepository.save(PlanMember.builder()
                .plan(ownedPlan)
                .user(other)
                .role(PlanMemberRole.OWNER)
                .build());

        mockMvc.perform(get("/api/user/profile/" + other.getUserId())
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(other.getUserId().toString()))
                .andExpect(jsonPath("$.nickname").value(other.getNickname()))
                .andExpect(jsonPath("$.myPlanCount").value(1))
                .andExpect(jsonPath("$.editablePlanCount").value(0))
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.birthdate").doesNotExist())
                .andExpect(jsonPath("$.gender").doesNotExist())
                .andExpect(jsonPath("$.isSocialLogin").doesNotExist());
    }

    @Test
    @DisplayName("타인 프로필 조회 실패 - 기본값이 비공개라 공개 설정을 켠 적 없는 사용자는 403")
    void getPublicProfile_fail_defaultsToPrivate() throws Exception {
        User other = userRepository.saveAndFlush(User.builder()
                .provider(UserProvider.LOCAL)
                .email("default-" + UUID.randomUUID() + "@example.com")
                .nickname("default-" + UUID.randomUUID().toString().substring(0, 8))
                .role(UserRole.USER)
                .build());

        assertThat(other.isProfilePublic()).isFalse();

        mockMvc.perform(get("/api/user/profile/" + other.getUserId())
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USER_002"));
    }

    @Test
    @DisplayName("타인 프로필 조회 실패 - 비공개 프로필이면 403, USER_002")
    void getPublicProfile_fail_privateProfile() throws Exception {
        User other = createOtherUser(false);

        mockMvc.perform(get("/api/user/profile/" + other.getUserId())
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USER_002"));
    }

    @Test
    @DisplayName("타인 프로필 조회 성공 - 비공개로 설정해도 본인은 자기 프로필을 볼 수 있음")
    void getPublicProfile_success_selfCanViewOwnPrivateProfile() throws Exception {
        testUser.changeProfilePublic(false);
        userRepository.saveAndFlush(testUser);

        mockMvc.perform(get("/api/user/profile/" + testUser.getUserId())
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value(testUser.getNickname()));
    }

    @Test
    @DisplayName("타인 프로필 조회 실패 - 존재하지 않는 사용자면 404, USER_001")
    void getPublicProfile_fail_userNotFound() throws Exception {
        mockMvc.perform(get("/api/user/profile/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_001"));
    }

    @Test
    @DisplayName("프로필 공개 설정 변경 성공 - 204, 공개/비공개 양방향 전환이 DB와 본인 프로필 응답에 반영됨")
    void changeProfileVisibility_success() throws Exception {
        mockMvc.perform(patch("/api/user/profile/visibility")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChangeProfileVisibilityRequest(true))))
                .andExpect(status().isNoContent());

        assertThat(userRepository.findById(testUser.getUserId()).orElseThrow().isProfilePublic()).isTrue();

        mockMvc.perform(get("/api/user/profile")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profilePublic").value(true));

        mockMvc.perform(patch("/api/user/profile/visibility")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChangeProfileVisibilityRequest(false))))
                .andExpect(status().isNoContent());

        assertThat(userRepository.findById(testUser.getUserId()).orElseThrow().isProfilePublic()).isFalse();

        mockMvc.perform(get("/api/user/profile")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profilePublic").value(false));
    }

    @Test
    @DisplayName("프로필 이미지 업로드 성공 - 200, URL이 DB와 프로필 응답에 반영됨")
    void changeProfileImage_success() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "avatar.png", "image/png", "fake-png-bytes".getBytes());

        String body = mockMvc.perform(multipart("/api/user/profile-image").file(file)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileImageUrl").exists())
                .andReturn().getResponse().getContentAsString();

        String url = objectMapper.readTree(body).get("profileImageUrl").asText();
        assertThat(url).contains("profile/" + testUser.getUserId() + "/");
        assertThat(userRepository.findById(testUser.getUserId()).orElseThrow().getProfileImageUrl()).isEqualTo(url);

        mockMvc.perform(get("/api/user/profile")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileImageUrl").value(url));
    }

    @Test
    @DisplayName("프로필 이미지 교체 성공 - URL이 새 값으로 바뀐다(같은 경로 덮어쓰기가 아님)")
    void changeProfileImage_success_replacesPreviousUrl() throws Exception {
        String first = uploadProfileImage("first.png");
        String second = uploadProfileImage("second.png");

        assertThat(second).isNotEqualTo(first);
        assertThat(userRepository.findById(testUser.getUserId()).orElseThrow().getProfileImageUrl()).isEqualTo(second);
        // 교체 시 이전 파일 정리를 요청한다 (저장소 낭비 방지)
        verify(profileImageStorage).delete(first);
    }

    @Test
    @DisplayName("프로필 이미지 업로드 실패 - 이미지가 아니면 400, USER_003")
    void changeProfileImage_fail_unsupportedType() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "resume.pdf", "application/pdf", "not-an-image".getBytes());

        mockMvc.perform(multipart("/api/user/profile-image").file(file)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("USER_003"));

        assertThat(userRepository.findById(testUser.getUserId()).orElseThrow().getProfileImageUrl()).isNull();
    }

    @Test
    @DisplayName("프로필 이미지 삭제 성공 - 204, URL이 null이 되어 Gravatar로 되돌아간다")
    void deleteProfileImage_success() throws Exception {
        uploadProfileImage("avatar.png");

        mockMvc.perform(delete("/api/user/profile-image")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent());

        assertThat(userRepository.findById(testUser.getUserId()).orElseThrow().getProfileImageUrl()).isNull();
    }

    @Test
    @DisplayName("프로필 이미지 업로드 실패 - 인증 토큰 없으면 401")
    void changeProfileImage_fail_noAuth() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "avatar.png", "image/png", "fake-png-bytes".getBytes());

        mockMvc.perform(multipart("/api/user/profile-image").file(file))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("타인 프로필 조회 - 공개 프로필이면 프로필 이미지 URL이 함께 내려온다")
    void getPublicProfile_includesProfileImage() throws Exception {
        User other = createOtherUser(true);
        other.changeProfileImageUrl("http://localhost:9000/planmate/profile/x/y.png");
        userRepository.saveAndFlush(other);

        mockMvc.perform(get("/api/user/profile/" + other.getUserId())
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileImageUrl").value("http://localhost:9000/planmate/profile/x/y.png"));
    }

    private String uploadProfileImage(String filename) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", filename, "image/png", ("bytes-of-" + filename).getBytes());
        String body = mockMvc.perform(multipart("/api/user/profile-image").file(file)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("profileImageUrl").asText();
    }

    @Test
    @DisplayName("프로필 공개 설정 변경 실패 - 인증 토큰 없으면 401")
    void changeProfileVisibility_fail_noAuth() throws Exception {
        mockMvc.perform(patch("/api/user/profile/visibility")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChangeProfileVisibilityRequest(false))))
                .andExpect(status().isUnauthorized());
    }

    private User createOtherUser(boolean profilePublic) {
        return userRepository.saveAndFlush(User.builder()
                .provider(UserProvider.LOCAL)
                .email("other-" + UUID.randomUUID() + "@example.com")
                .nickname("other-" + UUID.randomUUID().toString().substring(0, 8))
                .birthdate(LocalDate.of(1995, 3, 10))
                .gender(Gender.MALE)
                .role(UserRole.USER)
                .profilePublic(profilePublic)
                .build());
    }

    private Plan createPlanOwnedBy(User owner) {
        Destination destination = destinationRepository.save(Destination.builder()
                .destinationName("destination-" + UUID.randomUUID())
                .latitude(new BigDecimal("37.56330000"))
                .longitude(new BigDecimal("126.97970000"))
                .build());
        return planRepository.save(Plan.builder()
                .planName("부산 여행")
                .adultCount(1)
                .childCount(0)
                .transportationType(TransportationType.PUBLIC)
                .isShared(false)
                .user(owner)
                .destination(destination)
                .build());
    }

    @Test
    @DisplayName("닉네임 변경 성공 - 204, DB에 실제로 반영됨")
    void changeNickname_success() throws Exception {
        String newNickname = "new-" + UUID.randomUUID().toString().substring(0, 8);

        mockMvc.perform(patch("/api/user/nickname")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChangeNicknameRequest(newNickname))))
                .andExpect(status().isNoContent());

        assertThat(userRepository.findById(testUser.getUserId()).orElseThrow().getNickname()).isEqualTo(newNickname);
    }

    @Test
    @DisplayName("닉네임 변경 성공 - 자기 자신의 현재 닉네임으로 재제출해도 204(오탐 없음)")
    void changeNickname_success_sameNicknameDoesNotFalselyConflict() throws Exception {
        mockMvc.perform(patch("/api/user/nickname")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChangeNicknameRequest(testUser.getNickname()))))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("닉네임 변경 실패 - 다른 사용자가 이미 쓰는 닉네임이면 409, AUTH_005")
    void changeNickname_fail_duplicateNickname() throws Exception {
        User otherUser = userRepository.saveAndFlush(User.builder()
                .provider(UserProvider.LOCAL)
                .email("mypage-other-" + UUID.randomUUID() + "@example.com")
                .nickname("taken-" + UUID.randomUUID().toString().substring(0, 8))
                .role(UserRole.USER)
                .build());

        mockMvc.perform(patch("/api/user/nickname")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChangeNicknameRequest(otherUser.getNickname()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUTH_005"));
    }

    @Test
    @DisplayName("닉네임 변경 검증 실패 - 너무 짧으면 400, COMMON_001")
    void changeNickname_validationFailure_tooShort() throws Exception {
        mockMvc.perform(patch("/api/user/nickname")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChangeNicknameRequest("a"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    @DisplayName("닉네임 변경 실패 - 인증 토큰 없으면 401")
    void changeNickname_fail_noAuth() throws Exception {
        mockMvc.perform(patch("/api/user/nickname")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChangeNicknameRequest("anyNickname"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("생년월일 변경 성공 - 204, DB에 실제로 반영됨")
    void changeBirthdate_success() throws Exception {
        LocalDate newBirthdate = LocalDate.of(2000, 3, 15);

        mockMvc.perform(patch("/api/user/birthdate")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChangeBirthdateRequest(newBirthdate))))
                .andExpect(status().isNoContent());

        assertThat(userRepository.findById(testUser.getUserId()).orElseThrow().getBirthdate()).isEqualTo(newBirthdate);
    }

    @Test
    @DisplayName("생년월일 변경 검증 실패 - 미래 날짜면 400, COMMON_001")
    void changeBirthdate_validationFailure_futureDate() throws Exception {
        mockMvc.perform(patch("/api/user/birthdate")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChangeBirthdateRequest(LocalDate.now().plusDays(1)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    @DisplayName("생년월일 변경 실패 - 인증 토큰 없으면 401")
    void changeBirthdate_fail_noAuth() throws Exception {
        mockMvc.perform(patch("/api/user/birthdate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChangeBirthdateRequest(LocalDate.of(2000, 1, 1)))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("성별 변경 성공 - 204, DB에 실제로 반영됨(enum Converter 왕복 확인)")
    void changeGender_success() throws Exception {
        mockMvc.perform(patch("/api/user/gender")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChangeGenderRequest(Gender.OTHER))))
                .andExpect(status().isNoContent());

        assertThat(userRepository.findById(testUser.getUserId()).orElseThrow().getGender()).isEqualTo(Gender.OTHER);
    }

    @Test
    @DisplayName("성별 변경 성공 - MALE도 DB에 실제로 반영됨(enum Converter 왕복 확인 — FEMALE/OTHER만 있고 MALE은 빠져있던 것 보강)")
    void changeGender_success_male() throws Exception {
        mockMvc.perform(patch("/api/user/gender")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChangeGenderRequest(Gender.MALE))))
                .andExpect(status().isNoContent());

        assertThat(userRepository.findById(testUser.getUserId()).orElseThrow().getGender()).isEqualTo(Gender.MALE);
    }

    @Test
    @DisplayName("성별 변경 검증 실패 - 정의되지 않은 값이면 400, COMMON_001")
    void changeGender_validationFailure_invalidEnumValue() throws Exception {
        mockMvc.perform(patch("/api/user/gender")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gender\":\"INVALID\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    @DisplayName("성별 변경 실패 - 인증 토큰 없으면 401")
    void changeGender_fail_noAuth() throws Exception {
        mockMvc.perform(patch("/api/user/gender")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChangeGenderRequest(Gender.MALE))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("선호 테마 다중 카테고리 수정 성공 - 언급한 카테고리만 바뀌고, 언급 안 한 카테고리는 실제 DB 기준으로 그대로 남음")
    void changePreferredThemes_success_partialUpdateLeavesOtherCategoriesUntouched() throws Exception {
        PreferredTheme museum = findThemeByName("박물관");
        PreferredTheme park = findThemeByName("공원");
        PreferredTheme cafe = findThemeByName("카페");
        userPreferredThemeRepository.saveAll(List.of(
                UserPreferredTheme.builder().user(testUser).preferredTheme(museum).build(),
                UserPreferredTheme.builder().user(testUser).preferredTheme(cafe).build()));

        mockMvc.perform(patch("/api/user/preferredThemes")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChangePreferredThemesRequest(
                                Map.of(PreferredThemeCategory.ATTRACTION, List.of(park.getPreferredThemeId()))))))
                .andExpect(status().isNoContent());

        List<UserPreferredTheme> remaining = userPreferredThemeRepository.findByUserUserId(testUser.getUserId());
        assertThat(remaining).hasSize(2);
        assertThat(remaining.stream().map(upt -> upt.getPreferredTheme().getPreferredThemeName()))
                .containsExactlyInAnyOrder("공원", "카페");
    }

    @Test
    @DisplayName("선호 테마 다중 카테고리 수정 성공 - 값이 빈 배열이면 그 카테고리를 실제 DB에서 전부 비움")
    void changePreferredThemes_success_emptyListClearsCategory() throws Exception {
        PreferredTheme hotel = findThemeByName("호텔");
        userPreferredThemeRepository.save(UserPreferredTheme.builder().user(testUser).preferredTheme(hotel).build());

        mockMvc.perform(patch("/api/user/preferredThemes")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChangePreferredThemesRequest(
                                Map.of(PreferredThemeCategory.ACCOMMODATION, List.of())))))
                .andExpect(status().isNoContent());

        assertThat(userPreferredThemeRepository.findByUserUserId(testUser.getUserId())).isEmpty();
    }

    @Test
    @DisplayName("선호 테마 다중 카테고리 수정 실패 - 선언한 카테고리와 실제 테마 카테고리가 다르면 400, THEME_002")
    void changePreferredThemes_fail_categoryMismatch() throws Exception {
        PreferredTheme cafe = findThemeByName("카페");

        mockMvc.perform(patch("/api/user/preferredThemes")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChangePreferredThemesRequest(
                                Map.of(PreferredThemeCategory.ATTRACTION, List.of(cafe.getPreferredThemeId()))))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("THEME_002"));
    }

    @Test
    @DisplayName("선호 테마 다중 카테고리 수정 실패 - 존재하지 않는 테마 ID면 404, THEME_001")
    void changePreferredThemes_fail_themeNotFound() throws Exception {
        mockMvc.perform(patch("/api/user/preferredThemes")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChangePreferredThemesRequest(
                                Map.of(PreferredThemeCategory.ATTRACTION, List.of(999999))))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("THEME_001"));
    }

    @Test
    @DisplayName("선호 테마 다중 카테고리 수정 검증 실패 - 빈 Map이면 400, COMMON_001")
    void changePreferredThemes_validationFailure_emptyMap() throws Exception {
        mockMvc.perform(patch("/api/user/preferredThemes")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"themeUpdates\":{}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    @DisplayName("선호 테마 다중 카테고리 수정 실패 - 인증 토큰 없으면 401")
    void changePreferredThemes_fail_noAuth() throws Exception {
        mockMvc.perform(patch("/api/user/preferredThemes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChangePreferredThemesRequest(
                                Map.of(PreferredThemeCategory.ATTRACTION, List.of())))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("FCM 토큰 등록 성공 - 204, DB에 실제로 반영됨")
    void registerFcmToken_success() throws Exception {
        String fcmToken = "fcm-token-" + UUID.randomUUID();

        mockMvc.perform(post("/api/user/fcm-token")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterFcmTokenRequest(fcmToken))))
                .andExpect(status().isNoContent());

        assertThat(userRepository.findById(testUser.getUserId()).orElseThrow().getFcmToken()).isEqualTo(fcmToken);
    }

    @Test
    @DisplayName("FCM 토큰 등록 검증 실패 - 빈 값이면 400, COMMON_001")
    void registerFcmToken_validationFailure_blank() throws Exception {
        mockMvc.perform(post("/api/user/fcm-token")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterFcmTokenRequest(""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    @DisplayName("FCM 토큰 등록 실패 - 인증 토큰 없으면 401")
    void registerFcmToken_fail_noAuth() throws Exception {
        mockMvc.perform(post("/api/user/fcm-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterFcmTokenRequest("any-token"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("회원 탈퇴 성공 - 204, 하드 삭제가 아니라 소프트 삭제(row는 물리적으로 남고 deleted_at만 채워짐)")
    void resignAccount_success_softDeletesNotHardDeletes() throws Exception {
        mockMvc.perform(delete("/api/user/account")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent());

        // @SQLRestriction은 SQL 레벨 필터라 이미 1차 캐시에 올라온 관리 상태 엔티티는 그대로 반환한다.
        // 실제로 새로 SELECT를 태워야(= 운영 환경의 새 요청/새 영속성 컨텍스트와 동일한 조건) 걸러지는지
        // 확인할 수 있으므로, flush로 softDelete()를 먼저 DB에 반영한 뒤 캐시를 비우고 다시 조회한다.
        // clear()만 먼저 하면 아직 flush 안 된 softDelete() 변경 자체가 유실된다(그룹 A에서 만난 것과 같은 종류).
        entityManager.flush();
        entityManager.clear();
        assertThat(userRepository.findById(testUser.getUserId())).isEmpty();

        // 그러나 실제 DB 행은 물리적으로 남아있고 deleted_at만 채워짐 — 하드 삭제(deleteById)가 아님을 실측
        LocalDateTime deletedAt = jdbcTemplate.queryForObject(
                "SELECT deleted_at FROM users WHERE user_id = ?",
                LocalDateTime.class, testUser.getUserId());
        assertThat(deletedAt).isNotNull();
    }

    @Test
    @DisplayName("회원 탈퇴 성공 - 탈퇴 이후 같은 액세스 토큰으로 다른 API를 호출하면 404(USER_001), 401이 아님")
    void resignAccount_success_subsequentRequestWithSameTokenGetsUserNotFound() throws Exception {
        mockMvc.perform(delete("/api/user/account")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent());
        entityManager.flush();
        entityManager.clear(); // flush 먼저 해서 softDelete()를 반영한 뒤 캐시를 비워야 다음 조회가 실제로 DB에 SELECT를 태운다

        // JwtAuthenticationFilter는 서명/만료만 확인하고 DB는 안 보므로, 탈퇴 후에도 같은 토큰은
        // 인증 자체는 통과한다 — 그다음 Service의 userRepository.findById가 @SQLRestriction으로
        // 걸러내면서 UserNotFoundException(404)이 나는 것이지, 401이 나는 게 아니다.
        mockMvc.perform(get("/api/user/profile")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_001"));
    }

    @Test
    @DisplayName("회원 탈퇴 성공 - 탈퇴해도 소유했던 플랜은 그대로 남아 계속 조회됨(공유 플랜 보호, 연관 데이터 안 건드림)")
    void resignAccount_success_ownedPlanRemainsIntact() throws Exception {
        Destination destination = destinationRepository.save(Destination.builder()
                .destinationName("destination-" + UUID.randomUUID())
                .latitude(new BigDecimal("37.56330000"))
                .longitude(new BigDecimal("126.97970000"))
                .build());
        Plan ownedPlan = planRepository.save(Plan.builder()
                .planName("탈퇴 테스트 플랜")
                .adultCount(2)
                .childCount(0)
                .transportationType(TransportationType.PUBLIC)
                .isShared(true)
                .user(testUser)
                .destination(destination)
                .build());
        planMemberRepository.save(PlanMember.builder().plan(ownedPlan).user(testUser).role(PlanMemberRole.OWNER).build());

        mockMvc.perform(delete("/api/user/account")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent());

        entityManager.flush();
        entityManager.clear();

        // 소유자가 탈퇴해도 플랜 자체는 삭제/영향 없이 그대로 조회돼야 한다(공유 중이던 협업자 보호 목적).
        Plan stillExists = planRepository.findById(ownedPlan.getPlanId()).orElseThrow();
        assertThat(stillExists.getPlanName()).isEqualTo("탈퇴 테스트 플랜");
    }

    @Test
    @DisplayName("회원 탈퇴 실패 - 인증 토큰 없으면 401")
    void resignAccount_fail_noAuth() throws Exception {
        mockMvc.perform(delete("/api/user/account"))
                .andExpect(status().isUnauthorized());
    }

    private PreferredTheme findThemeByName(String name) {
        return preferredThemeRepository.findAll().stream()
                .filter(theme -> theme.getPreferredThemeName().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
