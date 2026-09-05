package com.planmate.common.exception.domain;

import com.planmate.common.exception.ErrorCode;
import com.planmate.common.exception.PlanMateException;

/**
 * [반영] 요청에 실린 raw-WS {@code sessionId}가 존재하지 않거나, 요청자 소유가 아니거나,
 * 이 plan 방에 join돼 있지 않음 — 위조가 아니라 신원 증명 실패.
 * project_ai/04_기능/ai/07_반영_reconcile_설계.md §9-1.
 */
public class ChatbotApplySessionInvalidException extends PlanMateException {

    public ChatbotApplySessionInvalidException() {
        super(ErrorCode.CHATBOT_APPLY_SESSION_INVALID);
    }
}
