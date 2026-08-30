package com.example.couponservice.coupon.entity;

import com.example.couponservice.coupon.exception.CouponErrorCode;
import com.example.couponservice.coupon.exception.CouponException;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CouponTest {

    private static final LocalDateTime START_AT = LocalDateTime.of(2026, 8, 30, 10, 0);
    private static final LocalDateTime END_AT = LocalDateTime.of(2026, 8, 31, 10, 0);

    @Test
    void createsCouponWithInitialRemainingQuantity() {
        String name = "10% discount coupon";
        int totalQuantity = 100;

        Coupon coupon = Coupon.create(
                name,
                totalQuantity,
                START_AT,
                END_AT
        );

        assertThat(coupon.getName()).isEqualTo(name);
        assertThat(coupon.getTotalQuantity()).isEqualTo(totalQuantity);
        assertThat(coupon.getRemainingQuantity()).isEqualTo(totalQuantity);
    }

    @Test
    void throwsInvalidNameWhenCouponNameIsBlank() {
        String name = " ";
        int totalQuantity = 100;

        assertThatThrownBy(() -> Coupon.create(
                name,
                totalQuantity,
                START_AT,
                END_AT
        )).isInstanceOfSatisfying(
                CouponException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(CouponErrorCode.INVALID_NAME)
        );
    }

    @Test
    void throwsInvalidTotalQuantityWhenQuantityIsZero() {
        String name = "10% discount coupon";
        int totalQuantity = 0;

        assertThatThrownBy(() -> Coupon.create(
                name,
                totalQuantity,
                START_AT,
                END_AT
        )).isInstanceOfSatisfying(
                CouponException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(CouponErrorCode.INVALID_TOTAL_QUANTITY)
        );
    }

    @Disabled("음수 수량 예외 코드 테스트 작성 예정")
    @Test
    void throwsInvalidTotalQuantityWhenQuantityIsNegative() {
    }

    @Disabled("발급 시작 시간이 종료 시간보다 늦은 경우 테스트 작성 예정")
    @Test
    void throwsInvalidPeriodWhenStartAtIsAfterEndAt() {
    }

    @Disabled("발급 시작 시간과 종료 시간이 같은 경우 테스트 작성 예정")
    @Test
    void throwsInvalidPeriodWhenStartAtEqualsEndAt() {
    }

    @Disabled("쿠폰 정상 발급 테스트 작성 예정")
    @Test
    void decreasesRemainingQuantityWhenCouponIsIssued() {
    }

    @Disabled("쿠폰 발급 시간 누락 테스트 작성 예정")
    @Test
    void throwsIssuedAtRequiredWhenIssuedAtIsNull() {
    }

    @Disabled("쿠폰 발급 시작 전 요청 테스트 작성 예정")
    @Test
    void throwsNotStartedWhenIssuedBeforeStartAt() {
    }

    @Disabled("쿠폰 발급 종료 후 요청 테스트 작성 예정")
    @Test
    void throwsExpiredWhenIssuedAfterEndAt() {
    }

    @Disabled("쿠폰 재고 소진 테스트 작성 예정")
    @Test
    void throwsSoldOutWhenNoQuantityRemains() {
    }

    @Disabled("발급 실패 시 재고 유지 테스트 작성 예정")
    @Test
    void keepsRemainingQuantityWhenIssueFails() {
    }
}
