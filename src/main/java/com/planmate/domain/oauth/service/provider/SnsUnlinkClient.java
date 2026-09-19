package com.planmate.domain.oauth.service.provider;

import com.planmate.domain.user.entity.User;
import com.planmate.domain.user.enums.UserProvider;

/**
 * 회원 탈퇴 시 SNS 연동을 끊는 provider별 전략. {@code OAuthProfileFetcher}와 같은 역할의
 * 짝(로그인 시 provider별 조회 vs 탈퇴 시 provider별 해제)이라 같은 패키지에 둔다.
 *
 * <p>{@link #supports()}가 {@code OAuthProvider}가 아니라 {@link UserProvider}를 반환하는
 * 이유는, 여기는 OAuth 로그인 라우팅이 아니라 이미 DB에 저장된 {@code User.provider}를 그대로
 * 키로 쓰는 맥락이기 때문이다(LOCAL 포함, 04_기능/OAuth/C_SNS_연동해제.md 2-3절).
 */
public interface SnsUnlinkClient {

    UserProvider supports();

    void unlink(User user);
}
