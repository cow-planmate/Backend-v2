package com.planmate.common.exception.domain;

import com.planmate.common.exception.ErrorCode;
import com.planmate.common.exception.PlanMateException;

/**
 * [반영] 시점에 SharedSync 캐시에 플랜이 있는데 읽지 못해 DB 로 폴백했다 — DB 는 주기 동기화(30초)만큼
 * 늦을 수 있어, 그 저장본으로 reconcile 하면 직전 [반영] 블록을 못 찾아 통째로 다시 만든다.
 * project_ai/04_기능/ai/07_반영_reconcile_설계.md §12-2.
 */
public class ChatbotApplySnapshotUnavailableException extends PlanMateException {

    public ChatbotApplySnapshotUnavailableException() {
        super(ErrorCode.CHATBOT_APPLY_SNAPSHOT_UNAVAILABLE);
    }
}
