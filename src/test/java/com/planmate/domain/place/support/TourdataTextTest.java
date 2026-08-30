package com.planmate.domain.place.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class TourdataTextTest {

    @Nested
    class BlankToNull {
        @Test
        void 공백이나_null은_null() {
            assertThat(TourdataText.blankToNull(null)).isNull();
            assertThat(TourdataText.blankToNull("")).isNull();
            assertThat(TourdataText.blankToNull("   ")).isNull();
        }

        @Test
        void 값이_있으면_그대로() {
            assertThat(TourdataText.blankToNull(" x ")).isEqualTo(" x ");
        }
    }

    @Nested
    class StripHtml {
        @Test
        void br은_개행으로_나머지_태그는_삭제() {
            assertThat(TourdataText.stripHtml("판매장 11:00~18:00<br>체험 예약제"))
                    .isEqualTo("판매장 11:00~18:00\n체험 예약제");
            assertThat(TourdataText.stripHtml("<p>경복궁은 <b>조선</b>의 법궁</p>"))
                    .isEqualTo("경복궁은 조선의 법궁");
        }

        @Test
        void br_변형들도_처리() {
            assertThat(TourdataText.stripHtml("a<br/>b<BR />c<br>d")).isEqualTo("a\nb\nc\nd");
        }

        @Test
        void 태그가_없으면_공백만_정리() {
            assertThat(TourdataText.stripHtml("연중무휴")).isEqualTo("연중무휴");
            assertThat(TourdataText.stripHtml("  상시   개방 ")).isEqualTo("상시 개방");
        }

        @Test
        void 비면_null() {
            assertThat(TourdataText.stripHtml(null)).isNull();
            assertThat(TourdataText.stripHtml("<br><br>")).isNull();
        }
    }

    @Nested
    class ExtractUrl {
        @Test
        void a태그의_href만_뽑는다() {
            assertThat(TourdataText.extractUrl("<a href=\"https://royal.khs.go.kr\">경복궁</a>"))
                    .isEqualTo("https://royal.khs.go.kr");
        }

        @Test
        void 작은따옴표_href도() {
            assertThat(TourdataText.extractUrl("<a href='http://example.com'>x</a>"))
                    .isEqualTo("http://example.com");
        }

        @Test
        void 라벨_섞인_평문에서_URL만() {
            assertThat(TourdataText.extractUrl("충주 문화관광 https://chungju.go.kr"))
                    .isEqualTo("https://chungju.go.kr");
        }

        @Test
        void URL이_아예_없으면_태그만_떼고_원문() {
            assertThat(TourdataText.extractUrl("준비중")).isEqualTo("준비중");
        }

        @Test
        void 비면_null() {
            assertThat(TourdataText.extractUrl(null)).isNull();
            assertThat(TourdataText.extractUrl("  ")).isNull();
        }
    }

    @Nested
    class FeeOrNull {
        @Test
        void 숫자문자열은_Long() {
            assertThat(TourdataText.feeOrNull("140000")).isEqualTo(140000L);
            assertThat(TourdataText.feeOrNull("120,000")).isEqualTo(120000L);
        }

        @Test
        void 영이나_빈값은_null_무료가_아니라_정보없음() {
            assertThat(TourdataText.feeOrNull("0")).isNull();
            assertThat(TourdataText.feeOrNull("")).isNull();
            assertThat(TourdataText.feeOrNull(null)).isNull();
        }

        @Test
        void 숫자가_아예_없으면_null() {
            assertThat(TourdataText.feeOrNull("문의")).isNull();
        }
    }

    @Nested
    class NormalizeInfoName {
        @Test
        void 내부_공백_제거() {
            assertThat(TourdataText.normalizeInfoName("입 장 료")).isEqualTo("입장료");
            assertThat(TourdataText.normalizeInfoName("관람료")).isEqualTo("관람료");
        }

        @Test
        void 비면_null() {
            assertThat(TourdataText.normalizeInfoName(null)).isNull();
            assertThat(TourdataText.normalizeInfoName("   ")).isNull();
        }
    }
}
