package com.planmate.domain.place.support;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * tourdata 캐시(TourAPI 원본)의 지저분한 문자열을 응답용으로 다듬는 정적 헬퍼.
 * 규칙 출처: project_ai/04_기능/ai/04_장소상세_데이터_필드_선정.md §9,
 * project_ai/04_기능/ai/05_장소상세_API_설계.md §7.
 */
public final class TourdataText {

    private static final Pattern BR_TAG = Pattern.compile("(?i)<br\\s*/?>");
    private static final Pattern ANY_TAG = Pattern.compile("<[^>]*>");
    private static final Pattern HREF = Pattern.compile("(?i)href\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern BARE_URL = Pattern.compile("(?i)https?://\\S+");
    private static final Pattern NON_DIGIT = Pattern.compile("[^0-9]");
    private static final Pattern HORIZONTAL_WS = Pattern.compile("[ \\t]+");
    private static final Pattern MANY_NEWLINES = Pattern.compile("\\n{3,}");

    private TourdataText() {
    }

    /** {@code ""}/공백만 → null. {@code PlaceCacheRepository.textOrNull}과 동일 규칙(§7-7). */
    public static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /**
     * HTML 태그 제거(§7-1). {@code <br>} 계열은 개행으로, 나머지 태그는 삭제, 수평 공백만 접는다(개행 보존).
     * 링크화는 하지 않는다 — 텍스트만 남긴다. 결과가 비면 null.
     */
    public static String stripHtml(String s) {
        if (blankToNull(s) == null) {
            return null;
        }
        String out = BR_TAG.matcher(s).replaceAll("\n");
        out = ANY_TAG.matcher(out).replaceAll("");
        out = HORIZONTAL_WS.matcher(out).replaceAll(" ");
        out = MANY_NEWLINES.matcher(out).replaceAll("\n\n");
        return blankToNull(out.trim());
    }

    /**
     * {@code homepage}에서 URL만 추출(§7-1). {@code href="..."}(작은/큰따옴표 다) 우선,
     * 없으면 문자열 안의 첫 {@code http(s)://...}, 그것도 없으면 원문(공백 정리). 결과가 비면 null.
     */
    public static String extractUrl(String s) {
        if (blankToNull(s) == null) {
            return null;
        }
        Matcher href = HREF.matcher(s);
        if (href.find()) {
            return blankToNull(href.group(1).trim());
        }
        Matcher bare = BARE_URL.matcher(s);
        if (bare.find()) {
            return blankToNull(bare.group().trim());
        }
        return blankToNull(ANY_TAG.matcher(s).replaceAll("").trim());
    }

    /**
     * 객실 요금(§7-2). 숫자만 추린 뒤 {@code Long}. {@code "0"}·빈값·파싱 불가 → null
     * (TourAPI에서 {@code "0"}은 "무료"가 아니라 "가격 정보 없음").
     */
    public static Long feeOrNull(String s) {
        if (blankToNull(s) == null) {
            return null;
        }
        String digits = NON_DIGIT.matcher(s).replaceAll("");
        if (digits.isEmpty()) {
            return null;
        }
        try {
            long value = Long.parseLong(digits);
            return value == 0L ? null : value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** {@code place_info_common.infoname} 내부 공백 제거(§7-6) — {@code "입 장 료"} → {@code "입장료"}. */
    public static String normalizeInfoName(String s) {
        if (blankToNull(s) == null) {
            return null;
        }
        return blankToNull(s.replace(" ", ""));
    }
}
