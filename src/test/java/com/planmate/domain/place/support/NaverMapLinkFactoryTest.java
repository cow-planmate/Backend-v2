package com.planmate.domain.place.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NaverMapLinkFactoryTest {

    @Test
    void title과_addr1을_공백으로_이어붙여_인코딩한다() {
        String url = NaverMapLinkFactory.buildSearchUrl("경복궁", "서울특별시 종로구 사직로 161");
        assertThat(url).isEqualTo(
                "https://map.naver.com/p/search/%EA%B2%BD%EB%B3%B5%EA%B6%81%20%EC%84%9C%EC%9A%B8%ED%8A%B9%EB%B3%84%EC%8B%9C%20%EC%A2%85%EB%A1%9C%EA%B5%AC%20%EC%82%AC%EC%A7%81%EB%A1%9C%20161");
    }

    @Test
    void title이_없으면_null() {
        assertThat(NaverMapLinkFactory.buildSearchUrl(null, "서울특별시 종로구")).isNull();
        assertThat(NaverMapLinkFactory.buildSearchUrl("  ", "서울특별시 종로구")).isNull();
    }

    @Test
    void addr1이_없으면_title_단독으로_폴백() {
        String url = NaverMapLinkFactory.buildSearchUrl("경복궁", null);
        assertThat(url).isEqualTo("https://map.naver.com/p/search/%EA%B2%BD%EB%B3%B5%EA%B6%81");

        assertThat(NaverMapLinkFactory.buildSearchUrl("경복궁", "   "))
                .isEqualTo("https://map.naver.com/p/search/%EA%B2%BD%EB%B3%B5%EA%B6%81");
    }

    @Test
    void 공백이_퍼센트20으로_인코딩된다() {
        String url = NaverMapLinkFactory.buildSearchUrl("O2 글램핑", "대구광역시 달성군");
        assertThat(url).doesNotContain("+");
        assertThat(url).contains("%20");
    }
}
