package com.planmate.domain.user.repository;

import com.planmate.domain.user.entity.User;
import com.planmate.domain.user.enums.UserProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    // LOCAL 로그인: LOWER(email) 인덱스와 일치하도록 @Query 사용
    // 활용 인덱스: uk_users_email ON (LOWER(email)) WHERE deleted_at IS NULL (provider 무관 전체 유니크로 확장됨)
    // @SQLRestriction이 deleted_at IS NULL 자동 삽입
    @Query("SELECT u FROM User u WHERE LOWER(u.email) = LOWER(:email) AND u.provider = 'LOCAL'")
    Optional<User> findLocalByEmail(@Param("email") String email);

    // OAuth 로그인: 활성 계정만 (uk_users_provider_provider_id 활용)
    // @SQLRestriction 자동 적용 → 탈퇴 계정 미포함
    Optional<User> findByProviderAndProviderId(UserProvider provider, String providerId);

    // OAuth 가입 완료 시 레이스 컨디션 방어용 중복 확인 — 활성 계정만 (04_기능/OAuth/A_진입.md 11-1절과 동일 이유)
    // 활용 인덱스: uk_users_provider_provider_id ON (provider, provider_id) WHERE deleted_at IS NULL
    // (V14 이전엔 deleted_at 조건이 없어 탈퇴 계정도 이 조합을 영구 점유했으나,
    //  이제 탈퇴 후에는 동일 provider+providerId로 재가입 시 새 계정이 생성된다 — 로컬과 동일 정책)
    boolean existsByProviderAndProviderId(UserProvider provider, String providerId);

    // 닉네임 중복 확인
    // 활용 인덱스: uk_users_nickname ON (nickname) WHERE deleted_at IS NULL
    // @SQLRestriction이 deleted_at IS NULL 자동 삽입 → 탈퇴 계정 닉네임 재사용 가능
    boolean existsByNickname(String nickname);

    // 닉네임으로 사용자 조회 — 협업 요청 생성(초대 대상 지정, collaboration_request/A_요청_생성.md 7-2절)
    // 활용 인덱스: uk_users_nickname ON (nickname) WHERE deleted_at IS NULL
    // 이메일과 달리 닉네임은 대소문자 무시 정책이 없어 LOWER() 없이 그대로 조회(existsByNickname과 동일 근거)
    Optional<User> findByNickname(String nickname);

    // LOCAL 이메일 중복 확인
    // @SQLRestriction 자동 삽입 → 탈퇴 후 동일 이메일 재가입 가능
    // LOWER() 명시 — uk_users_email 인덱스가 LOWER(email) 기준이라 파생 쿼리(대소문자 구분)로 두면 어긋남
    // (04_기능/인증/B_회원가입.md 7-2절 — existsByEmail과 동일한 이유로 수정, 아직 호출부 없어 회귀 위험 없음)
    @Query("SELECT CASE WHEN COUNT(u) > 0 THEN true ELSE false END FROM User u WHERE LOWER(u.email) = LOWER(:email) AND u.provider = :provider")
    boolean existsByEmailAndProvider(@Param("email") String email, @Param("provider") UserProvider provider);

    // 회원가입 시 이메일 중복 확인 — provider 무관 전체 (04_기능/인증/B_회원가입.md 10-2절)
    // 활용 인덱스: uk_users_email ON (LOWER(email)) WHERE deleted_at IS NULL
    // LOWER() 명시 — 파생 쿼리(대소문자 구분)로 두면 인덱스의 대소문자 무시 정책과 어긋남
    @Query("SELECT CASE WHEN COUNT(u) > 0 THEN true ELSE false END FROM User u WHERE LOWER(u.email) = LOWER(:email)")
    boolean existsByEmail(@Param("email") String email);

    // OAuth 콜백 이메일 충돌 확인 — provider 무관 전체 (04_기능/OAuth/A_진입.md 7-2절)
    // 활용 인덱스: uk_users_email ON (LOWER(email)) WHERE deleted_at IS NULL
    @Query("SELECT u FROM User u WHERE LOWER(u.email) = LOWER(:email)")
    Optional<User> findByEmailIgnoreCase(@Param("email") String email);

    // 내부 사용자 조회 — 탈퇴 계정 포함 (InternalUserQueryService)
    // nativeQuery = true → @SQLRestriction 미적용
    //
    // 커뮤니티가 탈퇴를 "응답에 없음"이 아니라 deleted 플래그로 받아야 하기 때문이다.
    // 탈퇴 계정을 빼버리면 조회 실패(백엔드 장애)와 구분되지 않아, 장애 때마다 멀쩡한 사용자가
    // 탈퇴한 것처럼 보인다. 노출 필드는 InternalUserQueryService가 탈퇴 여부에 따라 걸러낸다.
    @Query(value = "SELECT * FROM users WHERE user_id IN (:userIds)", nativeQuery = true)
    List<User> findAllByIdIncludingDeleted(@Param("userIds") Collection<UUID> userIds);

    // 커뮤니티 읽기 모델 최초 백필용 스냅샷 — 탈퇴 계정 포함, user_id 오름차순 키셋 페이징.
    // nativeQuery = true 이유는 위와 같다(@SQLRestriction 우회).
    //
    // OFFSET 이 아니라 커서(user_id > ?)를 쓰는 이유: 스냅샷을 보내는 동안에도 users 에
    // INSERT 가 들어오므로 OFFSET 은 행을 건너뛰거나 중복시킨다. user_id 는 UUIDv7 이라
    // 정렬이 안정적이고, 중복 전송은 어차피 멱등 업서트가 흡수한다.
    @Query(value = """
            SELECT * FROM users
             WHERE user_id > :afterUserId
             ORDER BY user_id
             LIMIT :limit
            """, nativeQuery = true)
    List<User> findSnapshotPageIncludingDeleted(@Param("afterUserId") UUID afterUserId,
                                                @Param("limit") int limit);
}
