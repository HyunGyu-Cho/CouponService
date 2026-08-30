package com.example.couponservice.coupon.entity;


import com.example.couponservice.coupon.exception.CouponErrorCode;
import com.example.couponservice.coupon.exception.CouponException;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class CouponTest {
    private final LocalDateTime startAt = LocalDateTime.of(2026, 8, 30, 10, 0);
    private final LocalDateTime endAt = LocalDateTime.of(2026, 8, 31, 10, 0);

    @Test
    // 쿠폰을 생성하면 잔여수량은 전체수량과 같다
    void successCouponCreate() {
        // given
        String name = "10% discount Coupon";
        int totalQuantity = 100;

        // when:
        Coupon coupon = Coupon.create(
                name,
                totalQuantity,
                startAt,
                endAt
        );

        // then
        // 정상결과는 assertThat으로 확인한다
        // assertThat(실제값).isEqualTo(예상값);
        assertThat(coupon.getName()).isEqualTo(name);
        assertThat(coupon.getTotalQuantity()).isEqualTo(totalQuantity);
        assertThat(coupon.getRemainingQuantity()).isEqualTo(totalQuantity);
    }

    @Test
    // 쿠폰 이름이 비어있으면 생성에 실패한다
    void failIfCouponNameIsEmpty() {
        // given
        String name = " ";
        int totalQuantity = 100;

        // when & then
        // 예외 발생 상황: assertThatThrownBy()로 확인한다
        // assertThatThrownBy( () -> 예외가 발생할 코드)
        //                 .isInstanceOf(예상한 예외.class);
        assertThatThrownBy( () ->
            Coupon.create(
                    name,
                    totalQuantity,
                    startAt,
                    endAt
            )
        )
                .isInstanceOfSatisfying(
                        CouponException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(CouponErrorCode.INVALID_NAME)
                );
    }

    @Test
    // 잘못된 전체 수량으로 쿠폰을 생성하면 실패하는지
    void failIfTotalQuantityIsZero() {
        // given
        String name = "10% discount coupon";
        int totalQuantity = 0;

        // when & then
        assertThatThrownBy( () ->
                Coupon.create(
                        name,
                        totalQuantity,
                        startAt,
                        endAt
                ))
                .isInstanceOfSatisfying(
                        CouponException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(CouponErrorCode.INVALID_TOTAL_QUANTITY)
                );
    }

    @Disabled("음수 수량 예외 코드 테스트 작성 예정")
    @Test
    // 음수 수량으로 쿠폰을 생성하면 실패하는지 확인
    void failIfTotalQuantityIsNegative() {
        // totalQuantity를 -1로 설정
    }

    @Disabled("발급 시작 시간이 종료 시간보다 늦은 경우 테스트 작성 예정")
    @Test
    void failIfStartAtIsAfterEndAt() {
        // TODO: startAt이 endAt보다 늦으면 INVALID_PERIOD가 발생하는지 검증
    }

    @Disabled("발급 시작 시간과 종료 시간이 같은 경우 테스트 작성 예정")
    @Test
    void failIfStartAtIsEqualToEndAt() {
        // TODO: startAt과 endAt이 같으면 INVALID_PERIOD가 발생하는지 검증
    }

    @Disabled("쿠폰 정상 발급 테스트 작성 예정")
    @Test
    void decreaseRemainingQuantityWhenCouponIsIssued() {
        // TODO: 쿠폰 발급 후 remainingQuantity가 1 감소하는지 검증
    }

    @Disabled("쿠폰 발급 시간 누락 테스트 작성 예정")
    @Test
    void failIfIssuedAtIsNull() {
        // TODO: issuedAt이 null이면 ISSUED_AT_REQUIRED가 발생하는지 검증
    }

    @Disabled("쿠폰 발급 시작 전 요청 테스트 작성 예정")
    @Test
    void failIfIssuedBeforeStartAt() {
        // TODO: startAt 이전에 발급하면 NOT_STARTED가 발생하는지 검증
    }

    @Disabled("쿠폰 발급 종료 후 요청 테스트 작성 예정")
    @Test
    void failIfIssuedAfterEndAt() {
        // TODO: endAt 이후에 발급하면 EXPIRED가 발생하는지 검증
    }

    @Disabled("쿠폰 재고 소진 테스트 작성 예정")
    @Test
    void failIfCouponIsSoldOut() {
        // TODO: 재고를 모두 발급한 후 추가 발급하면 SOLD_OUT이 발생하는지 검증
    }

    @Disabled("발급 실패 시 재고 유지 테스트 작성 예정")
    @Test
    void keepRemainingQuantityWhenIssueFails() {
        // TODO: 쿠폰 발급에 실패해도 remainingQuantity가 변경되지 않는지 검증
    }
}
