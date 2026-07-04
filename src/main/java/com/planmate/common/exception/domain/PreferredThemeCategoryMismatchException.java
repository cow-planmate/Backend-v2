package com.planmate.common.exception.domain;

import com.planmate.common.exception.ErrorCode;
import com.planmate.common.exception.PlanMateException;

public class PreferredThemeCategoryMismatchException extends PlanMateException {

    public PreferredThemeCategoryMismatchException() {
        super(ErrorCode.PREFERRED_THEME_CATEGORY_MISMATCH);
    }
}
