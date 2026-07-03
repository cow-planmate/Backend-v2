package com.planmate.domain.auth.service;

import com.planmate.common.exception.domain.DuplicateEmailException;
import com.planmate.common.exception.domain.InvalidVerificationCodeException;
import com.planmate.common.exception.domain.VerificationCodeNotFoundException;
import com.planmate.common.mail.MailService;
import com.planmate.domain.auth.dto.EmailVerificationConfirmRequest;
import com.planmate.domain.auth.dto.EmailVerificationConfirmResponse;
import com.planmate.domain.auth.enums.EmailVerificationPurpose;
import com.planmate.domain.auth.repository.EmailVerificationRedisRepository;
import com.planmate.domain.user.repository.UserRepository;
import com.planmate.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;

@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    private static final Duration CODE_TTL = Duration.ofMinutes(5);

    private static final String SIGN_UP_SUBJECT = "planMate 회원가입 인증번호를 확인해주세요";
    private static final String RESET_PASSWORD_SUBJECT = "planMate 비밀번호 재설정 인증번호를 확인해주세요";
    private static final String SIGN_UP_HEADLINE = "인증번호가 도착했어요";
    private static final String RESET_PASSWORD_HEADLINE = "비밀번호 재설정을 위한 인증번호입니다";
    private static final String SIGN_UP_DESCRIPTION = "아래 6자리 인증번호를 회원가입 화면에 입력하면 이메일 인증이 완료됩니다.";
    private static final String RESET_PASSWORD_DESCRIPTION = "아래 6자리 인증번호를 입력하면 비밀번호 재설정 절차를 이어서 진행할 수 있습니다.";

    private final UserRepository userRepository;
    private final SecureRandom secureRandom;
    private final JwtTokenProvider jwtTokenProvider;
    private final MailService mailService;
    private final EmailVerificationRedisRepository emailVerificationRedisRepository;

    @Transactional(readOnly = true)
    public void sendVerificationCode(String email, EmailVerificationPurpose purpose) {
        if (purpose == EmailVerificationPurpose.SIGN_UP && userRepository.existsByEmail(email)) {
            throw new DuplicateEmailException();
        }
        // RESET_PASSWORD는 계정 존재 여부와 무관하게 항상 코드를 생성/발송한다.
        // 존재 여부에 따라 분기해서 조기 반환하면 응답 시간 차이로 계정 존재가 새어나가는
        // 타이밍 사이드채널이 생기므로(C_이메일인증_구현_포트폴리오.md 8장), 분기 자체를 두지 않는다.

        int code = secureRandom.nextInt(900000) + 100000;
        emailVerificationRedisRepository.saveCode(email, purpose, code, CODE_TTL);

        mailService.sendVerificationCodeMail(
                email,
                getVerificationSubject(purpose),
                getVerificationHeadline(purpose),
                getVerificationDescription(purpose),
                String.valueOf(code));
    }

    public EmailVerificationConfirmResponse confirmVerificationCode(EmailVerificationConfirmRequest request) {
        int code = emailVerificationRedisRepository.findCode(request.email(), request.purpose())
                .orElseThrow(VerificationCodeNotFoundException::new);

        if (code != request.verificationCode()) {
            throw new InvalidVerificationCodeException();
        }
        emailVerificationRedisRepository.delete(request.email(), request.purpose());

        String token = jwtTokenProvider.createEmailVerificationToken(request.email(), request.purpose());
        return new EmailVerificationConfirmResponse(token);
    }

    private String getVerificationSubject(EmailVerificationPurpose purpose) {
        return switch (purpose) {
            case SIGN_UP -> SIGN_UP_SUBJECT;
            case RESET_PASSWORD -> RESET_PASSWORD_SUBJECT;
        };
    }

    private String getVerificationHeadline(EmailVerificationPurpose purpose) {
        return switch (purpose) {
            case SIGN_UP -> SIGN_UP_HEADLINE;
            case RESET_PASSWORD -> RESET_PASSWORD_HEADLINE;
        };
    }

    private String getVerificationDescription(EmailVerificationPurpose purpose) {
        return switch (purpose) {
            case SIGN_UP -> SIGN_UP_DESCRIPTION;
            case RESET_PASSWORD -> RESET_PASSWORD_DESCRIPTION;
        };
    }
}
