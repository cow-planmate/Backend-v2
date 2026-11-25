package com.planmate.domain.oauth.dto.google;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.planmate.domain.auth.dto.LoginResponse;

/**
 * 앱 네이티브 구글 로그인 결과. status 값은 웹 콜백 리다이렉트와 같은 이름을 쓴다.
 * <ul>
 *   <li>SUCCESS — {@code login}에 토큰(웹의 /exchange 결과와 같은 모양). 1회용 코드 교환 없이 바로 준다.</li>
 *   <li>NEED_ADDITIONAL_INFO — {@code signupId}, {@code needEmail}. 이어서 POST /api/oauth/complete.</li>
 * </ul>
 * 이메일 충돌·토큰 무효는 이 응답이 아니라 에러 응답(OAUTH_005 / OAUTH_006)으로 나간다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GoogleIdTokenLoginResponse(
        String status,
        LoginResponse login,
        String signupId,
        Boolean needEmail
) {

    public static GoogleIdTokenLoginResponse success(LoginResponse login) {
        return new GoogleIdTokenLoginResponse("SUCCESS", login, null, null);
    }

    public static GoogleIdTokenLoginResponse needAdditionalInfo(String signupId, boolean needEmail) {
        return new GoogleIdTokenLoginResponse("NEED_ADDITIONAL_INFO", null, signupId, needEmail);
    }
}
