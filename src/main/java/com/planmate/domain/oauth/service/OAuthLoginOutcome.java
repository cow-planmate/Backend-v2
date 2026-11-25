package com.planmate.domain.oauth.service;

import java.util.UUID;

/** {@link OAuthLoginService#resolve} 판정 결과. 웹은 리다이렉트로, 앱은 JSON으로 바꿔 돌려준다. */
public sealed interface OAuthLoginOutcome {

    /** 이미 가입된 활성 SNS 계정 → 바로 로그인 */
    record ExistingUser(UUID userId) implements OAuthLoginOutcome {}

    /** 신규 → 가입캐시 저장 완료, 추가정보 입력(/api/oauth/complete)으로 이어짐 */
    record NeedAdditionalInfo(String signupId, boolean needEmail) implements OAuthLoginOutcome {}

    /** 같은 이메일이 다른 가입 방식(로컬 또는 다른 SNS)으로 이미 있음 */
    record EmailConflict() implements OAuthLoginOutcome {}
}
