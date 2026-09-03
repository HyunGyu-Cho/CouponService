package com.example.couponservice.coupon.entity;

import com.example.couponservice.coupon.exception.CouponErrorCode;
import com.example.couponservice.coupon.exception.CouponException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "coupon")
public class Coupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "total_quantity", nullable = false)
    private int totalQuantity;

    @Column(name = "remaining_quantity", nullable = false)
    private int remainingQuantity;

    @Column(name = "start_at", nullable = false)
    private LocalDateTime startAt;

    @Column(name = "end_at", nullable = false)
    private LocalDateTime endAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private Coupon(
            String name,
            int totalQuantity,
            LocalDateTime startAt,
            LocalDateTime endAt
    ) {
        this.name = name;
        this.totalQuantity = totalQuantity;
        this.remainingQuantity = totalQuantity;
        this.startAt = startAt;
        this.endAt = endAt;
        this.createdAt = LocalDateTime.now();
    }

    public static Coupon create(
            String name,
            int totalQuantity,
            LocalDateTime startAt,
            LocalDateTime endAt
    ) {
        validateName(name);
        validateTotalQuantity(totalQuantity);
        validatePeriod(startAt, endAt);

        return new Coupon(name, totalQuantity, startAt, endAt);
    }

    public void issue(LocalDateTime issuedAt) {
        validateIssuable(issuedAt);
        remainingQuantity--;
    }

    public void validateIssuable(LocalDateTime issuedAt) {
        validateIssuedAt(issuedAt);
        validateIssuablePeriod(issuedAt);
        validateRemainingQuantity();

        remainingQuantity--;
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new CouponException(CouponErrorCode.INVALID_NAME);
        }
    }

    private static void validateTotalQuantity(int totalQuantity) {
        if (totalQuantity <= 0) {
            throw new CouponException(CouponErrorCode.INVALID_TOTAL_QUANTITY);
        }
    }

    private static void validatePeriod(
            LocalDateTime startAt,
            LocalDateTime endAt
    ) {
        if (startAt == null || endAt == null) {
            throw new CouponException(CouponErrorCode.INVALID_PERIOD);
        }

        if (!startAt.isBefore(endAt)) {
            throw new CouponException(CouponErrorCode.INVALID_PERIOD);
        }
    }

    private static void validateIssuedAt(LocalDateTime issuedAt) {
        if (issuedAt == null) {
            throw new CouponException(CouponErrorCode.ISSUED_AT_REQUIRED);
        }
    }

    private void validateIssuablePeriod(LocalDateTime issuedAt) {
        if (issuedAt.isBefore(startAt)) {
            throw new CouponException(CouponErrorCode.NOT_STARTED);
        }

        if (issuedAt.isAfter(endAt)) {
            throw new CouponException(CouponErrorCode.EXPIRED);
        }
    }

    private void validateRemainingQuantity() {
        if (remainingQuantity <= 0) {
            throw new CouponException(CouponErrorCode.SOLD_OUT);
        }
    }
}
