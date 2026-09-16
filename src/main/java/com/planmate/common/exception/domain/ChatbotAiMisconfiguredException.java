package com.planmate.common.exception.domain;

import com.planmate.common.exception.ErrorCode;
import com.planmate.common.exception.PlanMateException;

/**
 * planmate-ai 가 401(X-Internal-Token 불일치) / 422(요청 스키마 위반)를 응답 — v2 쪽 설정/조립 버그.
 * 사용자에겐 500 일반 메시지, 원인은 로그로. project_ai/04_기능/ai/06_챗봇_연동_설계.md §5-2.
 */
public class ChatbotAiMisconfiguredException extends PlanMateException {

    public ChatbotAiMisconfiguredException() {
        super(ErrorCode.CHATBOT_AI_MISCONFIGURED);
    }
}
