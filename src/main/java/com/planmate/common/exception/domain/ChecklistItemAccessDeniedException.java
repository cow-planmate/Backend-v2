package com.planmate.common.exception.domain;

import com.planmate.common.exception.ErrorCode;
import com.planmate.common.exception.PlanMateException;

public class ChecklistItemAccessDeniedException extends PlanMateException {

    public ChecklistItemAccessDeniedException() {
        super(ErrorCode.CHECKLIST_ITEM_ACCESS_DENIED);
    }
}
