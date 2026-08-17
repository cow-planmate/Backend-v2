package com.planmate.domain.oauth.service.provider;

import com.planmate.domain.oauth.dto.OAuthUserProfile;
import com.planmate.domain.oauth.enums.OAuthProvider;

/**
 * provider별 인가코드 → 사용자 프로필 조회 전략.
 *
 * <p>{@code state}는 NAVER 토큰 교환 API가 요청 바디에 그대로 요구하기 때문에 시그니처에 유지한다
 * (OAuthLoginService.callback() 원본 주석 참고). KAKAO/GOOGLE 구현체는 이 값을 사용하지 않는다.
 */
public interface OAuthProfileFetcher {

    OAuthProvider supports();

    OAuthUserProfile fetch(String code, String state);
}
