-- ------------------------------------------------------------
-- ShedLock 락 테이블
-- 인스턴스를 2개 이상 띄우면 @Scheduled 정리 잡이 노드마다 중복 실행된다.
-- ShedLock 이 이 테이블의 행 하나를 락으로 삼아 한 노드만 돌게 만든다.
--
-- 스키마는 ShedLock JdbcTemplateLockProvider 규약 그대로다(컬럼명·타입 고정).
-- lock_until 이 과거면 다른 노드가 가져갈 수 있다.
-- ------------------------------------------------------------
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
