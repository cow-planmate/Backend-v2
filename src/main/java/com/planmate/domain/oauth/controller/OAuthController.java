package com.planmate.domain.oauth.controller;

import com.planmate.common.exception.domain.UnsupportedOAuthProviderException;
import com.planmate.domain.auth.dto.LoginResponse;
import com.planmate.domain.oauth.config.OAuthProperties;
import com.planmate.domain.oauth.dto.OAuthCompleteRequest;
import com.planmate.domain.oauth.dto.OAuthExchangeRequest;
import com.planmate.domain.oauth.enums.OAuthProvider;
import com.planmate.domain.oauth.service.OAuthCompleteService;
import com.planmate.domain.oauth.service.OAuthExchangeService;
import com.planmate.domain.oauth.service.OAuthLoginService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/oauth")
public class OAuthController {

    private static final String STATE_COOKIE_NAME = "oauth_state";

    private final OAuthLoginService oAuthLoginService;
    private final OAuthExchangeService oAuthExchangeService;
    private final OAuthCompleteService oAuthCompleteService;
    private final OAuthProperties oAuthProperties;

    // 1) SNS 로그인 시작
    @GetMapping("/{provider}")
    public void redirectToProvider(@PathVariable String provider, HttpServletResponse response) throws IOException {
        try {
            OAuthProvider oAuthProvider = OAuthProvider.fromPath(provider);

            // NAVER만 CSRF 방어용 state 발급(10-1절, 더블 서브밋 쿠키)
            String state = (oAuthProvider == OAuthProvider.NAVER) ? UUID.randomUUID().toString() : null;

            String authorizeUrl = oAuthLoginService.redirectToProvider(oAuthProvider, state);

            if (oAuthProvider == OAuthProvider.NAVER) {
                setStateCookie(response, state);
            }

            response.sendRedirect(authorizeUrl);
        } catch (Exception e) {
            redirectOnFailure(response, e);
        }
    }

    // 2) SNS 로그인 콜백
    @GetMapping("/{provider}/callback")
    public void callback(@PathVariable String provider,
                          @RequestParam String code,
                          @RequestParam(required = false) String state,
                          HttpServletRequest request,
                          HttpServletResponse response) throws IOException {
        try {
            OAuthProvider oAuthProvider = OAuthProvider.fromPath(provider);

            // NAVER는 쿠키-쿼리파라미터 대조로 CSRF 검증, 실패하면 Service 호출 없이 바로 실패 리다이렉트(10-1절)
            if (oAuthProvider == OAuthProvider.NAVER && !isStateValid(request, state)) {
                response.sendRedirect(buildFailRedirect("INVALID_STATE"));
                return;
            }

            String redirectUrl = oAuthLoginService.callback(oAuthProvider, code, state);
            response.sendRedirect(redirectUrl);
        } catch (Exception e) {
            redirectOnFailure(response, e);
        }
    }

    // 3) 인가코드 교환 (B그룹)
    @PostMapping("/exchange")
    public ResponseEntity<LoginResponse> exchange(@Valid @RequestBody OAuthExchangeRequest request) {
        LoginResponse response = oAuthExchangeService.exchange(request.code());
        return ResponseEntity.ok(response);
    }

    // 4) 가입 완료 (B그룹)
    @PostMapping("/complete")
    public ResponseEntity<LoginResponse> complete(@Valid @RequestBody OAuthCompleteRequest request) {
        LoginResponse response = oAuthCompleteService.complete(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // 진입 두 메서드에서만 이 헬퍼를 호출, exchange/complete(B그룹)는 건드리지 않음(GlobalExceptionHandler로 정상 흘러감)
    private void redirectOnFailure(HttpServletResponse response, Exception e) throws IOException {
        log.warn("OAuth 진입/콜백 처리 실패", e);
        String reason = (e instanceof UnsupportedOAuthProviderException) ? "UNSUPPORTED_PROVIDER" : "UNKNOWN";
        response.sendRedirect(buildFailRedirect(reason));
    }

    private String buildFailRedirect(String reason) {
        return UriComponentsBuilder
                .fromUriString(oAuthProperties.getFrontendRedirectUri())
                .queryParam("status", "FAIL")
                .queryParam("reason", reason)
                .build(true)
                .toUriString();
    }

    private void setStateCookie(HttpServletResponse response, String state) {
        ResponseCookie cookie = ResponseCookie.from(STATE_COOKIE_NAME, state)
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")   // 네이버발 크로스사이트 리다이렉트에서도 쿠키가 실려오려면 Strict 불가
                .maxAge(Duration.ofMinutes(5))
                .path("/api/oauth")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private boolean isStateValid(HttpServletRequest request, String returnedState) {
        if (request.getCookies() == null || returnedState == null) {
            return false;
        }
        return Arrays.stream(request.getCookies())
                .filter(c -> STATE_COOKIE_NAME.equals(c.getName()))
                .anyMatch(c -> c.getValue().equals(returnedState));
    }
}
