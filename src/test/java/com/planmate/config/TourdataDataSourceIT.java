package com.planmate.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * tourdata(공공데이터 캐시) DataSource 배선 검증.
 *
 * <p>{@code docker-compose.local.yml} 의 {@code tourdata-postgres}(localhost:5433) 가 떠 있어야 한다.
 * CI 러너에는 그 컨테이너가 없으므로 {@code @Tag("external")} 로 기본 {@code test} 태스크에서 제외한다
 * (build.gradle: {@code useJUnitPlatform { excludeTags 'external' }}). 로컬 검증은
 * {@code docker compose -f docker-compose.local.yml up -d} 후 {@code ./gradlew externalTest}.
 */
@Tag("external")
@SpringBootTest
class TourdataDataSourceIT {

    @Autowired
    @Qualifier("tourdataDataSource")
    DataSource tourdataDataSource;

    @Autowired
    @Qualifier("tourdataJdbcTemplate")
    NamedParameterJdbcTemplate tourdataJdbcTemplate;

    @Test
    void 앱_DB와_별개인_tourdata에_읽기전용으로_연결된다() throws Exception {
        try (Connection connection = tourdataDataSource.getConnection()) {
            assertThat(connection.getCatalog()).isEqualTo("tourdata");
            assertThat(connection.isReadOnly()).isTrue();
        }
    }

    @Test
    void 캐시_테이블을_조회할_수_있다() {
        Long count = tourdataJdbcTemplate.queryForObject(
                "SELECT count(*) FROM place_list", Map.of(), Long.class);
        assertThat(count).isGreaterThan(40_000L);
    }
}
