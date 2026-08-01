package com.planmate.domain.user.event;

import java.util.UUID;

/**
 * 사용자 프로필(닉네임·사진·공개 여부)이 바뀌었음을 알리는 도메인 이벤트.
 *
 * 커뮤니티 서비스가 작성자 정보를 캐싱하고 있어서, 바뀐 사실을 알려주지 않으면
 * 남의 피드·댓글에 최대 10분간 옛 정보가 남는다.
 *
 * 변경된 ID만 싣는다. 값을 실어 보내면 이벤트 순서가 뒤바뀔 때 옛 값이 캐시에
 * 남을 수 있으므로, 수신측이 다음 조회에서 최신값을 받아가게 한다.
 */
public record UserProfileChangedEvent(UUID userId) {
}
