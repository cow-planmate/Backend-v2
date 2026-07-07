package com.planmate.common.sse;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Redis SSE 채널에 실어보내는 페이로드. JSON으로 직렬화되어 어느 노드든 역직렬화해서 로컬 emitter로 전달할 수 있다.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class SseNotificationMessage {
    private String userId;
    private String eventName;
    private Object data;
}
