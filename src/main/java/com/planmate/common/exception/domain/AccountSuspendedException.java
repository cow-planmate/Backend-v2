package com.planmate.common.exception.domain;

import com.planmate.common.exception.ErrorCode;
import com.planmate.common.exception.PlanMateException;
import com.planmate.domain.user.entity.User;

import java.time.LocalDateTime;

public class AccountSuspendedException extends PlanMateException {

    public AccountSuspendedException(LocalDateTime until, String reason) {
        super(ErrorCode.ACCOUNT_SUSPENDED, buildMessage(until, reason));
    }

    private static String buildMessage(LocalDateTime until, String reason) {
        boolean permanent = User.PERMANENT_SUSPENSION_UNTIL.equals(until);
        String base = permanent ? "영구 정지된 계정입니다." : until + "까지 정지된 계정입니다.";
        return reason != null ? base + " 사유: " + reason : base;
    }
}
