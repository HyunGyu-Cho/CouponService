package com.example.couponservice.coupon.exception;

import com.example.couponservice.global.exception.BusinessException;

// 쿠폰 도메인에서 발생한 오류임을 표현하는 전용 예외
public class CouponException extends BusinessException {
    public CouponException(CouponErrorCode errorCode) {
        super(errorCode);
    }
}
