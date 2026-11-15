package com.planmate.domain.destination.cache;

/**
 * 미리 만들어 둔 응답 본문과 그 ETag. ETag는 본문 JSON 바이트의 SHA-256(hex) — 내용만으로 결정되므로
 * 여러 파드에서 같은 값이 나온다(B_계층_상세.md §5-2).
 */
public record CachedResponse<T>(T body, String etag) {
}
