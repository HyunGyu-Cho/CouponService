package com.example.couponservice.coupon.entity;

import com.example.couponservice.coupon.exception.CouponErrorCode;
import com.example.couponservice.coupon.exception.CouponException;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CouponTest {

    private static final LocalDateTime START_AT = LocalDateTime.of(2026, 8, 30, 10, 0);
    private static final LocalDateTime END_AT = LocalDateTime.of(2026, 8, 31, 10, 0);
    private static final LocalDateTime IN_PERIOD = LocalDateTime.of(2026, 8, 30, 15, 0);

    private static Coupon coupon(int totalQuantity) {
        return Coupon.create("10% discount coupon", totalQuantity, START_AT, END_AT);
    }

    private static void assertCouponError(
            Runnable action,
            CouponErrorCode expected
    ) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(
                CouponException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(expected)
        );
    }

    @Test
    void createsCouponWithInitialRemainingQuantity() {
        Coupon coupon = coupon(100);

        assertThat(coupon.getName()).isEqualTo("10% discount coupon");
        assertThat(coupon.getTotalQuantity()).isEqualTo(100);
        assertThat(coupon.getRemainingQuantity()).isEqualTo(100);
    }

    @Test
    void throwsInvalidNameWhenCouponNameIsBlank() {
        assertCouponError(
                () -> Coupon.create(" ", 100, START_AT, END_AT),
                CouponErrorCode.INVALID_NAME
        );
    }

    @Test
    void throwsInvalidTotalQuantityWhenQuantityIsZero() {
        assertCouponError(() -> coupon(0), CouponErrorCode.INVALID_TOTAL_QUANTITY);
    }

    @Test
    void throwsInvalidTotalQuantityWhenQuantityIsNegative() {
        assertCouponError(() -> coupon(-1), CouponErrorCode.INVALID_TOTAL_QUANTITY);
    }

    @Test
    void throwsInvalidPeriodWhenStartAtIsAfterEndAt() {
        assertCouponError(
                () -> Coupon.create("coupon", 100, END_AT, START_AT),
                CouponErrorCode.INVALID_PERIOD
        );
    }

    @Test
    void throwsInvalidPeriodWhenStartAtEqualsEndAt() {
        assertCouponError(
                () -> Coupon.create("coupon", 100, START_AT, START_AT),
                CouponErrorCode.INVALID_PERIOD
        );
    }

    @Test
    void decreasesRemainingQuantityWhenCouponIsIssued() {
        Coupon coupon = coupon(100);

        coupon.issue(IN_PERIOD);

        assertThat(coupon.getRemainingQuantity()).isEqualTo(99);
    }

    @Test
    void throwsIssuedAtRequiredWhenIssuedAtIsNull() {
        assertCouponError(() -> coupon(100).issue(null), CouponErrorCode.ISSUED_AT_REQUIRED);
    }

    @Test
    void throwsNotStartedWhenIssuedBeforeStartAt() {
        assertCouponError(
                () -> coupon(100).issue(START_AT.minusSeconds(1)),
                CouponErrorCode.NOT_STARTED
        );
    }

    @Test
    void throwsExpiredWhenIssuedAfterEndAt() {
        assertCouponError(
                () -> coupon(100).issue(END_AT.plusSeconds(1)),
                CouponErrorCode.EXPIRED
        );
    }

    @Test
    void issuesAtPeriodBoundaries() {
        Coupon coupon = coupon(2);

        coupon.issue(START_AT);
        coupon.issue(END_AT);

        assertThat(coupon.getRemainingQuantity()).isZero();
    }

    @Test
    void throwsSoldOutWhenNoQuantityRemains() {
        Coupon coupon = coupon(1);
        coupon.issue(IN_PERIOD);

        assertCouponError(() -> coupon.issue(IN_PERIOD), CouponErrorCode.SOLD_OUT);
    }

    @Test
    void keepsRemainingQuantityWhenIssueFails() {
        Coupon coupon = coupon(1);

        assertCouponError(() -> coupon.issue(END_AT.plusDays(1)), CouponErrorCode.EXPIRED);

        assertThat(coupon.getRemainingQuantity()).isEqualTo(1);
    }

    @Test
    void validateIssuableDoesNotChangeRemainingQuantity() {
        Coupon coupon = coupon(1);

        coupon.validateIssuable(IN_PERIOD);
        coupon.validateIssuable(IN_PERIOD);

        assertThat(coupon.getRemainingQuantity()).isEqualTo(1);
    }

    @Test
    void validateIssuableThrowsSoldOutWithoutChangingState() {
        Coupon coupon = coupon(1);
        coupon.issue(IN_PERIOD);

        assertCouponError(() -> coupon.validateIssuable(IN_PERIOD), CouponErrorCode.SOLD_OUT);

        assertThat(coupon.getRemainingQuantity()).isZero();
    }
}
