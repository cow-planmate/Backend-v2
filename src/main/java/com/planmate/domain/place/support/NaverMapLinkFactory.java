package com.planmate.domain.place.support;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 네이버 지도 검색 링크 생성. 네이버 API 호출·크롤링 없이 {@code title}+{@code addr1}만으로
 * 결정적 URL을 만든다. 이름 단독 검색은 동명이인·흔한 상호에 취약해서(2026-09 실측 검증)
 * 주소를 붙인 검색어를 쓴다.
 */
public final class NaverMapLinkFactory {

    private static final String SEARCH_BASE_URL = "https://map.naver.com/p/search/";

    private NaverMapLinkFactory() {
    }

    /** {@code title}이 없으면 검색 자체가 무의미해 null. {@code addr1}만 없으면 title 단독으로 폴백. */
    public static String buildSearchUrl(String title, String addr1) {
        String normalizedTitle = TourdataText.blankToNull(title);
        if (normalizedTitle == null) {
            return null;
        }
        String normalizedAddr = TourdataText.blankToNull(addr1);
        String query = normalizedAddr == null ? normalizedTitle : normalizedTitle + " " + normalizedAddr;
        return SEARCH_BASE_URL + encode(query);
    }

    private static String encode(String query) {
        return URLEncoder.encode(query, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
