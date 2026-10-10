package com.planmate.domain.user.controller;

import com.planmate.domain.user.entity.User;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /api/admin/users 회원 검색의 권한과 응답 모양 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminUserSearchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private String adminToken;
    private String userToken;
    private User member;

    @BeforeEach
    void setUp() {
        User admin = saveUser(UserRole.ADMIN, "관리자");
        adminToken = jwtTokenProvider.createAccessToken(admin.getUserId(), admin.getRole().name());

        User user = saveUser(UserRole.USER, "일반");
        userToken = jwtTokenProvider.createAccessToken(user.getUserId(), user.getRole().name());

        member = saveUser(UserRole.USER, "검색대상");
    }

    @Test
    @DisplayName("회원 검색 - ADMIN이 닉네임으로 찾으면 정지 여부와 함께 돌려준다")
    void search_success_admin() throws Exception {
        mockMvc.perform(get("/api/admin/users")
                        .param("keyword", member.getNickname())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].userId").value(member.getUserId().toString()))
                .andExpect(jsonPath("$.items[0].suspended").value(false));
    }

    @Test
    @DisplayName("회원 검색 - 정지 중만 보기를 켜면 정지되지 않은 회원은 나오지 않는다")
    void search_suspendedOnly_excludesActiveMembers() throws Exception {
        mockMvc.perform(get("/api/admin/users")
                        .param("keyword", member.getNickname())
                        .param("suspendedOnly", "true")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @DisplayName("회원 검색 실패 - 일반 사용자는 403")
    void search_fail_ordinaryUser() throws Exception {
        mockMvc.perform(get("/api/admin/users")
                        .param("keyword", member.getNickname())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("정지 실패 - 관리자 계정은 다른 관리자가 정지할 수 없다")
    void suspend_fail_otherAdmin() throws Exception {
        User otherAdmin = saveUser(UserRole.ADMIN, "다른관리자");

        mockMvc.perform(patch("/api/admin/users/" + otherAdmin.getUserId() + "/suspend")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"until\":\"2099-01-01T00:00:00\",\"reason\":\"테스트\"}"))
                .andExpect(status().isBadRequest());
    }

    private User saveUser(UserRole role, String prefix) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return userRepository.saveAndFlush(User.builder()
                .provider(UserProvider.LOCAL)
                .email(prefix + "-" + suffix + "@example.com")
                .nickname(prefix + "-" + suffix)
                .role(role)
                .build());
    }
}
