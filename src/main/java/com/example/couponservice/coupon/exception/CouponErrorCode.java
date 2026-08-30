package com.example.couponservice.coupon.exception;

import com.example.couponservice.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/*
    INVALID_NAME	400	COUPON_001	잘못된 이름
    INVALID_TOTAL_QUANTITY	400	COUPON_002	잘못된 전체 수량
    INVALID_PERIOD	400	COUPON_003	잘못된 발급 기간
    ISSUED_AT_REQUIRED	400	COUPON_004	발급 시간 누락
    NOT_STARTED	409	COUPON_005	발급 시작 전
    EXPIRED	409	COUPON_006	발급 기간 종료
    SOLD_OUT	409	COUPON_007	재고 소진
*/
@Getter
@RequiredArgsConstructor
public enum CouponErrorCode implements ErrorCode {
    INVALID_NAME(
            HttpStatus.BAD_REQUEST,
            "COUPON_001",
            "쿠폰 이름은 비어 있을 수 없습니다."
    ),

    INVALID_TOTAL_QUANTITY(
            HttpStatus.BAD_REQUEST,
            "COUPON_002",
            "쿠폰 전체 수량은 1개 이상이어야 합니다."
    ),

    INVALID_PERIOD(
            HttpStatus.BAD_REQUEST,
            "COUPON_003",
            "쿠폰 발급 기간이 올바르지 않습니다."
    ),

    ISSUED_AT_REQUIRED(
            HttpStatus.BAD_REQUEST,
            "COUPON_004",
            "쿠폰 발급 시간은 필수입니다."
    ),

    NOT_STARTED(
            HttpStatus.CONFLICT,
            "COUPON_005",
            "쿠폰 발급 기간이 시작되지 않았습니다."
    ),

    EXPIRED(
            HttpStatus.CONFLICT,
            "COUPON_006",
            "쿠폰 발급 기간이 종료되었습니다."
    ),

    SOLD_OUT(
            HttpStatus.CONFLICT,
            "COUPON_007",
            "쿠폰 재고가 모두 소진되었습니다."
    );

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
