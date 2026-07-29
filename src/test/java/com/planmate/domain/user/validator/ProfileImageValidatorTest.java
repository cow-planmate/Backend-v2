package com.planmate.domain.user.validator;

import com.planmate.common.exception.ErrorCode;
import com.planmate.common.exception.PlanMateException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProfileImageValidatorTest {

    private final ProfileImageValidator validator = new ProfileImageValidator();

    @Test
    @DisplayName("허용된 이미지 형식은 통과한다")
    void allowsSupportedImageTypes() {
        for (String contentType : new String[]{"image/jpeg", "image/png", "image/gif", "image/webp"}) {
            assertThatCode(() -> validator.validate(
                    new MockMultipartFile("file", "a.img", contentType, "bytes".getBytes())))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("이미지가 아니면 PROFILE_IMAGE_UNSUPPORTED_TYPE")
    void rejectsNonImage() {
        assertThatThrownBy(() -> validator.validate(
                new MockMultipartFile("file", "a.pdf", "application/pdf", "bytes".getBytes())))
                .isInstanceOf(PlanMateException.class)
                .satisfies(e -> assertThat(((PlanMateException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PROFILE_IMAGE_UNSUPPORTED_TYPE));
    }

    @Test
    @DisplayName("Content-Type이 없으면 통과시키지 않는다 (형식을 확인할 수 없으면 거부)")
    void rejectsMissingContentType() {
        assertThatThrownBy(() -> validator.validate(
                new MockMultipartFile("file", "a.png", null, "bytes".getBytes())))
                .isInstanceOf(PlanMateException.class)
                .satisfies(e -> assertThat(((PlanMateException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PROFILE_IMAGE_UNSUPPORTED_TYPE));
    }

    @Test
    @DisplayName("5MB를 넘으면 PROFILE_IMAGE_TOO_LARGE")
    void rejectsOversizedFile() {
        byte[] tooLarge = new byte[5 * 1024 * 1024 + 1];

        assertThatThrownBy(() -> validator.validate(
                new MockMultipartFile("file", "big.png", "image/png", tooLarge)))
                .isInstanceOf(PlanMateException.class)
                .satisfies(e -> assertThat(((PlanMateException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PROFILE_IMAGE_TOO_LARGE));
    }

    @Test
    @DisplayName("빈 파일이면 INVALID_INPUT")
    void rejectsEmptyFile() {
        assertThatThrownBy(() -> validator.validate(
                new MockMultipartFile("file", "a.png", "image/png", new byte[0])))
                .isInstanceOf(PlanMateException.class)
                .satisfies(e -> assertThat(((PlanMateException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_INPUT));
    }
}
