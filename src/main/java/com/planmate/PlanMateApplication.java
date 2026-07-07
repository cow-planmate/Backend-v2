package com.planmate;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

// RedisAutoConfiguration 제외 이유: config.RedisConnectionConfig가 메인/Pub-Sub 커넥션 팩토리를
// 전부 직접 만든다(향후 Sentinel 활성화 대비). application.yml에 spring.data.redis.sentinel.master/nodes
// 키가 빈 문자열로라도 존재하면 Boot의 RedisConnectionConfiguration 생성자가 "Sentinel Master Id must not
// be null or empty"로 즉시 죽는다(값이 비어 있어도 섹션 존재 자체를 Sentinel 모드로 오판) — 자동구성 자체를 꺼서 회피.
@SpringBootApplication(exclude = RedisAutoConfiguration.class)
@EnableScheduling
public class PlanMateApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlanMateApplication.class, args);
    }
}
