package com.planmate.config;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * DataSource 2개를 명시적으로 선언한다.
 *
 * <p>Spring Boot 의 {@code DataSourceAutoConfiguration} 은 {@code DataSource} 빈이 컨텍스트에 하나라도
 * 있으면 통째로 backoff 한다({@code @ConditionalOnMissingBean(DataSource.class)}). 아래 tourdata
 * DataSource 를 추가하는 순간 앱 DB(primary)까지 자동구성이 사라지므로, primary 도 여기서 직접 만든다.
 * Flyway · JPA · 자동 구성되는 {@code JdbcTemplate} 은 {@code @Primary} DataSource 를 자동으로 집는다.
 *
 * <p>tourdata: 공공데이터(한국관광공사 TourAPI) 캐시 DB. 앱 DB 와 <b>별개 인스턴스</b>이며 <b>읽기 전용</b>이다.
 * 스키마는 이 앱이 관리하지 않는다(덤프 pg_restore + 최신화 잡이 채운다).
 * 설계: {@code project_ai/04_기능/ai/01_인프라설계.md}.
 */
@Configuration
public class DataSourceConfig {

    // ── 앱 DB (기존 spring.datasource) — 자동구성 backoff 때문에 명시적으로 재선언 ──────────────

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties primaryDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource dataSource(
            @Qualifier("primaryDataSourceProperties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    // ── 공공데이터 캐시 DB (tourdata.datasource) — 읽기 전용 2번째 ─────────────────────────────

    @Bean
    @ConfigurationProperties("tourdata.datasource")
    public DataSourceProperties tourdataDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @ConfigurationProperties("tourdata.datasource.hikari")
    public HikariDataSource tourdataDataSource(
            @Qualifier("tourdataDataSourceProperties") DataSourceProperties properties) {
        HikariDataSource dataSource = properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
        dataSource.setReadOnly(true);
        dataSource.setPoolName("tourdata-pool");
        // 저빈도 읽기 전용이라 기본값(10)보다 작게. tourdata.datasource.hikari.* 로 오버라이드 가능
        // (이 @ConfigurationProperties 바인딩이 메서드 반환 뒤에 적용되므로 여기서 준 값이 기본값이 됨).
        dataSource.setMaximumPoolSize(5);
        // 컨텍스트 로드를 이 DB 가용성에 묶지 않는다: 부팅 시 커넥션을 검증하지 않고 첫 쿼리에서 실패한다.
        //  - 캐시 DB 장애가 앱 전체 기동을 막지 않는다(추천장소·장소상세만 영향).
        //  - CI / 다른 개발자의 @SpringBootTest 가 이 컨테이너 없이도 컨텍스트를 띄운다
        //    (캐시를 실제로 조회하는 테스트만 @Tag("external") 로 분리).
        dataSource.setInitializationFailTimeout(-1);
        return dataSource;
    }

    @Bean
    public NamedParameterJdbcTemplate tourdataJdbcTemplate(
            @Qualifier("tourdataDataSource") DataSource tourdataDataSource) {
        return new NamedParameterJdbcTemplate(tourdataDataSource);
    }
}
