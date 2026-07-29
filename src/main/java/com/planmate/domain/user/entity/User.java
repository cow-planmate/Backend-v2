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
}
