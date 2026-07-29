package com.planmate.domain.user.validator;

import com.planmate.common.exception.ErrorCode;
import com.planmate.common.exception.PlanMateException;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.util.Set;

/**
 * 프로필 이미지 업로드 요청 검증.
 * 저장 백엔드(MinIO)와 무관한 정책이라 저장소 어댑터가 아니라 도메인 쪽에 둔다.
 */
@Component
public class ProfileImageValidator {

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/png", "image/gif", "image/webp");
    private static final long MAX_SIZE_BYTES = 5 * 1024 * 1024;

    public void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new PlanMateException(ErrorCode.INVALID_INPUT, "업로드할 파일이 없습니다.");
        }
        if (file.getSize() > MAX_SIZE_BYTES) {
            throw new PlanMateException(ErrorCode.PROFILE_IMAGE_TOO_LARGE);
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new PlanMateException(ErrorCode.PROFILE_IMAGE_UNSUPPORTED_TYPE);
        }
    }
}
