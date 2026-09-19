package com.planmate.domain.oauth.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class OAuthUserProfile {

    private String providerId;   // SNS 고유 ID
    private String email;        // 이메일
    private String nickname;     // 닉네임
    private String refreshToken; // OAuth refresh_token. GOOGLE만 채움(KAKAO/NAVER는 null, 04_기능/OAuth/C_SNS_연동해제.md 0-3절)
}
