package com.example.couponservice.coupon.entity;

import com.example.couponservice.coupon.exception.CouponErrorCode;
import com.example.couponservice.coupon.exception.CouponException;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "coupon_issue",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_coupon_issue_coupon_user",
                columnNames = {"coupon_id", "user_id"}
        )
)
public class CouponIssue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "coupon_id", nullable = false)
    private Coupon coupon;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    private CouponIssue(
            Coupon coupon,
            Long userId,
            LocalDateTime issuedAt
    ) {
        this.coupon = coupon;
        this.userId = userId;
        this.issuedAt = issuedAt;
    }

    public static CouponIssue create(
            Coupon coupon,
            Long userId,
            LocalDateTime issuedAt
    ) {
        validateCouponRequired(coupon);
        validateUserId(userId);
        validateIssuedAt(issuedAt);

        return new CouponIssue(coupon, userId, issuedAt);
    }

    private static void validateCouponRequired(Coupon coupon) {
        if (coupon == null) {
            throw new IllegalArgumentException("쿠폰은 필수입니다.");
        }
    }

    private static void validateUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new CouponException(CouponErrorCode.INVALID_USER_ID);
        }
    }

    private static void validateIssuedAt(LocalDateTime issuedAt) {
        if (issuedAt == null) {
            throw new CouponException(CouponErrorCode.ISSUED_AT_REQUIRED);
        }
    }
}
