package com.planmate.domain.feedback.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planmate.domain.feedback.repository.BetaFeedbackRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BetaFeedbackControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BetaFeedbackRepository betaFeedbackRepository;

    // ---------- POST /api/beta/feedback ----------

    @Test
    @DisplayName("피드백 제출 성공 - 인증 헤더 없이도 201로 저장되고 실제 DB에 반영된다")
    void submitFeedback_success_noAuthRequired() throws Exception {
        long beforeCount = betaFeedbackRepository.count();

        mockMvc.perform(post("/api/beta/feedback")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("content", "테스트 피드백입니다"))))
                .andExpect(status().isCreated());

        assertThat(betaFeedbackRepository.count()).isEqualTo(beforeCount + 1);
        assertThat(betaFeedbackRepository.findAll())
                .anyMatch(f -> f.getContent().equals("테스트 피드백입니다") && f.getCreatedAt() != null);
    }

    @Test
    @DisplayName("피드백 제출 실패 - content가 공백이면 400(COMMON_001), 저장되지 않는다")
    void submitFeedback_fail_blankContent() throws Exception {
        long beforeCount = betaFeedbackRepository.count();

        mockMvc.perform(post("/api/beta/feedback")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("content", "   "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));

        assertThat(betaFeedbackRepository.count()).isEqualTo(beforeCount);
    }
}
