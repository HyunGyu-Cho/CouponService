package com.example.couponservice.coupon.exception;

import com.example.couponservice.global.exception.BusinessException;

public class CouponException extends BusinessException {
    public CouponException(CouponErrorCode errorCode) {
        super(errorCode);
    }
}
