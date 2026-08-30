package com.example.couponservice.coupon.exception;

import com.example.couponservice.global.exception.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

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
    ),

    COUPON_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "COUPON_008",
            "쿠폰을 찾을 수 없습니다."
    ),

    INVALID_USER_ID(
            HttpStatus.BAD_REQUEST,
            "COUPON_009",
            "사용자 ID는 1 이상이어야 합니다."
    ),

    DUPLICATE_ISSUE(
            HttpStatus.CONFLICT,
            "COUPON_010",
            "이미 발급받은 쿠폰입니다."
    );

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
