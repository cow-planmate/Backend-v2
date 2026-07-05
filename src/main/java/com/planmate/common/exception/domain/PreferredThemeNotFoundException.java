package com.planmate.common.exception.domain;

import com.planmate.common.exception.ErrorCode;
import com.planmate.common.exception.PlanMateException;

public class PreferredThemeNotFoundException extends PlanMateException {

    public PreferredThemeNotFoundException() {
        super(ErrorCode.PREFERRED_THEME_NOT_FOUND);
    }
}
