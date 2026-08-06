package com.planmate.common.exception.domain;

import com.planmate.common.exception.ErrorCode;
import com.planmate.common.exception.PlanMateException;

public class ChecklistItemNotFoundException extends PlanMateException {

    public ChecklistItemNotFoundException() {
        super(ErrorCode.CHECKLIST_ITEM_NOT_FOUND);
    }
}
