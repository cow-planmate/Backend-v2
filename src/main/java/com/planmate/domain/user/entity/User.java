package com.planmate.domain.user.entity;

import com.github.f4b6a3.uuid.UuidCreator;
import com.planmate.common.entity.BaseSoftDeleteEntity;
import com.planmate.domain.user.enums.Gender;
import com.planmate.domain.user.enums.UserProvider;
import com.planmate.domain.user.enums.UserRole;
import com.planmate.domain.user.enums.converter.GenderConverter;
import com.sharedsync.shared.presence.annotation.PresenceUser;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDate;
import java.util.UUID;

@Getter
@Entity
@Table(name = "users")
@SQLRestriction("deleted_at IS NULL")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
@PresenceUser(fields = {"nickname", "email"})
public class User extends BaseSoftDeleteEntity {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserProvider provider;

    @Column(name = "provider_id", length = 100)
    private String providerId;

    /**
     * 생성 시점에만 정해진다 — 이메일을 바꾸는 경로는 없다(변경자 메서드도 없다).
     *
     * <p>커뮤니티 읽기 모델이 이 전제에 기대고 있다. avatar_hash 는 이메일 MD5 인데,
     * {@code UserOutboxWriter} 가 outbox 기록 시점에 한 번 계산해서 복제해 두므로
     * 이메일이 바뀌면 복제본의 아바타가 영구히 낡는다. 이메일 변경 기능을 추가한다면
     * 반드시 {@code userOutboxWriter.publish(user)} 를 같은 트랜잭션에서 호출할 것.
     */
    @Column(length = 255)
    private String email;

    @Column(length = 255)
    private String password;

    @Column(nullable = false, length = 100)
    private String nickname;

    private LocalDate birthdate;

    @Convert(converter = GenderConverter.class)
    @Column(length = 10)
    private Gender gender;

    @Column(name = "fcm_token", columnDefinition = "TEXT")
    private String fcmToken;

    /** 프로필 이미지 공개 URL. null이면 클라이언트가 이메일 기반 Gravatar로 대체한다. */
    @Column(name = "profile_image_url", length = 500)
    private String profileImageUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private UserRole role = UserRole.USER;

    /**
     * 프로필 공개 여부 — 기본 비공개.
     * false면 타인은 프로필도, 프로필에 묶인 게시글 목록도 볼 수 없다(본인 조회는 항상 허용).
     */
    @Column(name = "profile_public", nullable = false)
    @Builder.Default
    private boolean profilePublic = false;

    @PrePersist
    void assignId() {
        if (this.userId == null) {
            this.userId = UuidCreator.getTimeOrderedEpoch();
        }
    }

    public void changePassword(String newEncodedPassword) {
        this.password = newEncodedPassword;
    }

    public void changeNickname(String newNickname) {
        this.nickname = newNickname;
    }

    public void changeBirthdate(LocalDate newBirthdate) {
        this.birthdate = newBirthdate;
    }

    public void changeGender(Gender newGender) {
        this.gender = newGender;
    }

    public void changeFcmToken(String newFcmToken) {
        this.fcmToken = newFcmToken;
    }

    public void changeProfilePublic(boolean newProfilePublic) {
        this.profilePublic = newProfilePublic;
    }

    public void changeProfileImageUrl(String newProfileImageUrl) {
        this.profileImageUrl = newProfileImageUrl;
    }
}
