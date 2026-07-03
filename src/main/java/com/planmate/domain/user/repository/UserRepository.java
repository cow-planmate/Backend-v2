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
    // 활용 인덱스: uk_users_local_email ON (LOWER(email)) WHERE provider='LOCAL' AND deleted_at IS NULL
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
    boolean existsByEmailAndProvider(String email, UserProvider provider);
}
