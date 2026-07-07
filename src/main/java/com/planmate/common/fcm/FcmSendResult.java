package com.planmate.common.fcm;

public enum FcmSendResult {
    SUCCESS,
    SKIPPED_NO_TOKEN,
    /** UNREGISTERED / INVALID_ARGUMENT — 호출자가 토큰 정리 여부 판단 */
    INVALID_TOKEN,
    FAILED_OTHER
}
