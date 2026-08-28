package com.planmate.domain.user.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planmate.common.audit.entity.AdminActionLog;
import com.planmate.common.audit.enums.AdminActionType;
import com.planmate.common.audit.repository.AdminActionLogRepository;
import com.planmate.domain.user.dto.SuspendUserRequest;
import com.planmate.domain.user.entity.User;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminUserControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AdminActionLogRepository adminActionLogRepository;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private EntityManager entityManager;

    private User admin;
    private User targetUser;
    private String adminToken;
    private String userToken;

    @BeforeEach
    void setUp() {
        admin = userRepository.saveAndFlush(User.builder()
                .provider(UserProvider.LOCAL)
                .email("admin-suspend-" + UUID.randomUUID() + "@example.com")
                .nickname("admin-" + UUID.randomUUID().toString().substring(0, 8))
                .role(UserRole.ADMIN)
                .build());
        adminToken = jwtTokenProvider.createAccessToken(admin.getUserId(), admin.getRole().name());

        targetUser = userRepository.saveAndFlush(User.builder()
                .provider(UserProvider.LOCAL)
                .email("target-suspend-" + UUID.randomUUID() + "@example.com")
                .nickname("target-" + UUID.randomUUID().toString().substring(0, 8))
                .role(UserRole.USER)
                .build());
        userToken = jwtTokenProvider.createAccessToken(targetUser.getUserId(), targetUser.getRole().name());
    }

    // ---------- PATCH /api/admin/users/{userId}/suspend ----------

    @Test
    @DisplayName("정지 성공 - 204, 실제 DB에 suspended_until/suspend_reason이 반영되고 감사로그가 남는다")
    void suspend_success() throws Exception {
        LocalDateTime until = LocalDateTime.now().plusDays(7).withNano(0);
        SuspendUserRequest request = new SuspendUserRequest(until, "커뮤니티 이용약관 위반 - 반복 도배");

        mockMvc.perform(patch("/api/admin/users/{userId}/suspend", targetUser.getUserId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNoContent());

        entityManager.flush();
        entityManager.clear();

        User reloaded = userRepository.findById(targetUser.getUserId()).orElseThrow();
        assertThat(reloaded.getSuspendedUntil()).isEqualTo(until);
        assertThat(reloaded.getSuspendReason()).isEqualTo("커뮤니티 이용약관 위반 - 반복 도배");
        assertThat(reloaded.isSuspended()).isTrue();

        List<AdminActionLog> logs = adminActionLogRepository.findAll();
        assertThat(logs).anyMatch(log ->
                log.getActionType() == AdminActionType.SUSPEND_USER
                        && log.getTargetId().equals(targetUser.getUserId().toString())
                        && log.getAdminUserId().equals(admin.getUserId()));
    }

    @Test
    @DisplayName("정지 성공 - until 생략 시 영구 정지 sentinel로 저장된다")
    void suspend_success_permanent() throws Exception {
        SuspendUserRequest request = new SuspendUserRequest(null, "심각한 위반");

        mockMvc.perform(patch("/api/admin/users/{userId}/suspend", targetUser.getUserId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNoContent());

        entityManager.flush();
        entityManager.clear();

        User reloaded = userRepository.findById(targetUser.getUserId()).orElseThrow();
        assertThat(reloaded.getSuspendedUntil()).isEqualTo(User.PERMANENT_SUSPENSION_UNTIL);
    }

    @Test
    @DisplayName("정지 실패 - 인증 헤더 없으면 401(COMMON_002)")
    void suspend_fail_noAuth() throws Exception {
        SuspendUserRequest request = new SuspendUserRequest(null, "사유");

        mockMvc.perform(patch("/api/admin/users/{userId}/suspend", targetUser.getUserId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_002"));
    }

    @Test
    @DisplayName("정지 실패 - 로그인했지만 ADMIN이 아니면 403(COMMON_003)")
    void suspend_fail_notAdmin() throws Exception {
        SuspendUserRequest request = new SuspendUserRequest(null, "사유");

        mockMvc.perform(patch("/api/admin/users/{userId}/suspend", targetUser.getUserId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_003"));
    }

    @Test
    @DisplayName("정지 실패 - 사유 미입력이면 400(COMMON_001, Bean Validation)")
    void suspend_fail_blankReason() throws Exception {
        SuspendUserRequest request = new SuspendUserRequest(null, "  ");

        mockMvc.perform(patch("/api/admin/users/{userId}/suspend", targetUser.getUserId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    @DisplayName("정지 실패 - 자기 자신 정지 시도는 400(COMMON_001)")
    void suspend_fail_self() throws Exception {
        SuspendUserRequest request = new SuspendUserRequest(null, "사유");

        mockMvc.perform(patch("/api/admin/users/{userId}/suspend", admin.getUserId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    @DisplayName("정지 실패 - 존재하지 않는 유저는 404(USER_001)")
    void suspend_fail_userNotFound() throws Exception {
        SuspendUserRequest request = new SuspendUserRequest(null, "사유");

        mockMvc.perform(patch("/api/admin/users/{userId}/suspend", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_001"));
    }

    // ---------- DELETE /api/admin/users/{userId}/suspend ----------

    @Test
    @DisplayName("정지 해제 성공 - 204, 실제 DB에서 suspended_until/suspend_reason이 비워지고 감사로그가 남는다")
    void unsuspend_success() throws Exception {
        targetUser.suspend(LocalDateTime.now().plusDays(1), "이전 사유");
        userRepository.saveAndFlush(targetUser);
        entityManager.clear();

        mockMvc.perform(delete("/api/admin/users/{userId}/suspend", targetUser.getUserId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        entityManager.flush();
        entityManager.clear();

        User reloaded = userRepository.findById(targetUser.getUserId()).orElseThrow();
        assertThat(reloaded.getSuspendedUntil()).isNull();
        assertThat(reloaded.getSuspendReason()).isNull();
        assertThat(reloaded.isSuspended()).isFalse();

        List<AdminActionLog> logs = adminActionLogRepository.findAll();
        assertThat(logs).anyMatch(log ->
                log.getActionType() == AdminActionType.UNSUSPEND_USER
                        && log.getTargetId().equals(targetUser.getUserId().toString()));
    }

    @Test
    @DisplayName("정지 해제 실패 - 로그인했지만 ADMIN이 아니면 403(COMMON_003)")
    void unsuspend_fail_notAdmin() throws Exception {
        mockMvc.perform(delete("/api/admin/users/{userId}/suspend", targetUser.getUserId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_003"));
    }
}
