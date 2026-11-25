# Backend-v2

PlanMate 메인 서버. 여러 사람이 동시에 접속해 여행 일정을 함께 편집하는 실시간 협업 여행 플래너의 백엔드다.
회원·일정·협업 권한 같은 REST API 와, 일정 동시 편집(WebSocket, [SharedSync](https://github.com/Shared-Sync/SharedSync))을 함께 제공한다.

서비스: https://www.planmate.site

```
프론트 ── Gateway(JWT 검증) ──▶ Backend-v2 ─┬─ PostgreSQL        앱 DB (Flyway 마이그레이션)
                                  │          ├─ PostgreSQL        tourdata 캐시 DB (공공데이터, 읽기 전용)
                                  │          ├─ Redis             동시 편집 캐시 · 토큰
                                  │          ├─ MinIO             프로필 이미지
                                  │          ├─ planmate-ai       AI 일정 챗봇 (REST)
                                  │          └─ Kafka             알림 이벤트 (outbox)
                                  └─ WebSocket /ws  일정 동시 편집 (SharedSync)
```

## 주요 기능

| 도메인 | 내용 |
|---|---|
| `auth` · `oauth` | 이메일 인증 회원가입·로그인, 비밀번호 재설정, 소셜 로그인(Google·Kakao·Naver), RS256 JWT |
| `user` | 마이페이지, 프로필 이미지, 관리자 회원 관리 |
| `plan` · `timetable` | 여행 일정 생성·조회·삭제, 날짜별 타임테이블과 장소 블록 |
| `collaboration` | 일정 초대, 편집 권한 요청·수락·거절 |
| `checklist` | 일정 공용 체크리스트와 개인 체크리스트 |
| `place` | 추천 장소(tourdata 캐시 DB), 키워드 검색, 장소 상세 |
| `route` | 길찾기(자동차·도보·대중교통)와 결과 캐싱 |
| `destination` · `theme` · `weather` | 여행지 목록, 선호 테마, 날씨 |
| `chatbot` | planmate-ai 대화 중계, 챗봇 결과를 실제 일정에 반영([반영]) |
| `notification` | 알림 outbox → Kafka, FCM·SSE |
| `feedback` | 베타 피드백 수집, 관리자 조회 |

일정 동시 편집은 SharedSync 가 맡는다. 편집은 Redis 캐시에 먼저 쓰고, 주기적으로 DB 에 반영한다.

## 기술 스택

Java 17 · Spring Boot 3.5 · Spring Data JPA · Spring Security · PostgreSQL · Flyway · Redis · WebSocket(SharedSync) ·
gRPC(내부 서비스 간, 계약은 BSR) · Kafka · MinIO · JUnit 5 · Testcontainers

## 패키지 구조

```
src/main/java/com/planmate
├── domain/<도메인>/   controller · service · repository · entity · dto  (도메인별 수직 분리)
├── common/            예외 표준화, 감사, 외부 API, 메일, 스토리지, FCM·SSE, 내부 API 인증
├── security/          JWT 발급·검증, 인증 필터
├── config/            Spring 설정
└── filter/            요청 필터
src/main/resources
├── application.yml
└── db/migration/      Flyway 마이그레이션 (V1~)
```

## 로컬 실행

필요: JDK 17, Docker(PostgreSQL · Redis · MinIO), 테스트용 Docker(Testcontainers).

```bash
cp .env.example .env     # 값 채우기 — 아래 세 값은 없으면 기동하지 않는다
./deploy/gen-jwt-key.sh  # JWT_PRIVATE_KEY (RS256, PKCS#8)
openssl rand -hex 32     # INTERNAL_API_TOKEN
openssl rand -base64 32  # SNS_REFRESH_TOKEN_ENC_KEY
./gradlew bootRun        # .env 는 bootRun 이 자동으로 읽는다
```

- API 문서(Swagger): `/swagger-ui.html`
- 포트: HTTP `server.port`, 내부 gRPC `9090`, 관리(actuator) `9091`
- OAuth 클라이언트 키 등 나머지 비밀값은 `src/main/resources/application-secret.yml`(gitignore)에 둔다.
- 각 환경변수 설명은 [`.env.example`](.env.example) 참고.

## 테스트

```bash
./gradlew test
```

단위 테스트(Mockito)와 실제 PostgreSQL 컨테이너(Testcontainers)를 쓰는 통합 테스트로 이루어져 있다.

## 배포

`.github/workflows/deploy.yml` — push 하면 Docker 이미지를 빌드해 Docker Hub 에 올린다.

- `test` 브랜치 → `:test` 이미지, 테스트 서버가 자동으로 따라간다.
- `main` 브랜치 → 운영 이미지, deploy 저장소 태그를 갱신해 ArgoCD 가 운영(k3s)에 반영한다.

## 관련 저장소

- [planmate-ai](https://github.com/cow-planmate/planmate-ai) — AI 일정 챗봇 서버
- [planmate-monitor](https://github.com/cow-planmate/planmate-monitor) — 운영 모니터링 대시보드
- [SharedSync](https://github.com/Shared-Sync/SharedSync) — 실시간 동시 편집 프레임워크
- [Community](https://github.com/cow-planmate/Community) — 커뮤니티 서버
- [planmate2.0](https://github.com/cow-planmate/planmate2.0) — 프론트엔드
