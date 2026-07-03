package com.planmate.domain.plan.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.f4b6a3.uuid.UuidCreator;
import com.planmate.common.entity.BaseSoftDeleteEntity;
import com.planmate.domain.destination.entity.Destination;
import com.planmate.domain.plan.enums.TransportationType;
import com.planmate.domain.plan.enums.converter.TransportationTypeConverter;
import com.planmate.domain.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.util.UUID;

@Getter
@Entity
@Table(name = "plan")
@SQLRestriction("deleted_at IS NULL")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Plan extends BaseSoftDeleteEntity {

    @Id
    @Column(name = "plan_id")
    private UUID planId;

    @Column(name = "plan_name", nullable = false, length = 100)
    private String planName;

    @Column(name = "adult_count", nullable = false)
    private int adultCount;

    @Column(name = "child_count", nullable = false)
    private int childCount;

    @Convert(converter = TransportationTypeConverter.class)
    @Column(name = "transportation_type", nullable = false, length = 20)
    private TransportationType transportationType;

    @JsonProperty("isShared")
    @Column(name = "is_shared", nullable = false)
    private boolean isShared;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "destination_id", nullable = false)
    private Destination destination;

    @PrePersist
    void assignId() {
        if (this.planId == null) {
            this.planId = UuidCreator.getTimeOrderedEpoch();
        }
    }
}
