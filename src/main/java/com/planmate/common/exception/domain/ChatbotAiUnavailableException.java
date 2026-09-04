package com.planmate.common.exception.domain;

import com.planmate.common.exception.ErrorCode;
import com.planmate.common.exception.PlanMateException;

/**
 * planmate-ai 가 일시적으로 응답하지 못함 — 502/503, connect refused/timeout, read timeout.
 * project_ai/04_기능/ai/06_챗봇_연동_설계.md §5-2.
 */
public class ChatbotAiUnavailableException extends PlanMateException {

    public ChatbotAiUnavailableException() {
        super(ErrorCode.CHATBOT_AI_UNAVAILABLE);
    }
}
