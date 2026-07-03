package com.planmate.domain.user.repository;

import com.planmate.domain.user.entity.User;
import com.planmate.domain.user.enums.UserProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    // OAuth 재가입 — 탈퇴 계정 포함 조회 (DB 설계 6.8절 복구 플로우)
    // nativeQuery = true → @SQLRestriction 미적용
    // 활용 인덱스: uk_users_provider_provider_id (deleted_at IS NULL 조건 없음)
    @Query(value = "SELECT * FROM users WHERE provider = :provider AND provider_id = :providerId",
           nativeQuery = true)
    Optional<User> findByProviderAndProviderIdIncludingDeleted(@Param("provider") String provider,
                                                               @Param("providerId") String providerId);

    // 닉네임 중복 확인
    // 활용 인덱스: uk_users_nickname ON (nickname) WHERE deleted_at IS NULL
    // @SQLRestriction이 deleted_at IS NULL 자동 삽입 → 탈퇴 계정 닉네임 재사용 가능
    boolean existsByNickname(String nickname);

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
}
