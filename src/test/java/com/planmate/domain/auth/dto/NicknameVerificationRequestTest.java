package com.planmate.domain.auth.dto;

import com.planmate.domain.user.dto.ChangeNicknameRequest;
import jakarta.validation.Validation;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.assertThat;

class NicknameVerificationRequestTest {
    @ParameterizedTest
    @CsvSource({"1,false", "2,true", "20,true", "21,false", "24,false"})
    void verificationAndSaveAcceptTheSameLengths(int length, boolean valid) {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            String nickname = "가".repeat(length);
            assertThat(validator.validate(new NicknameVerificationRequest(nickname)).isEmpty()).isEqualTo(valid);
            assertThat(validator.validate(new ChangeNicknameRequest(nickname)).isEmpty()).isEqualTo(valid);
            assertThat(validator.validate(new NicknameVerificationRequest(" ".repeat(length)))).isNotEmpty();
        }
    }
}
