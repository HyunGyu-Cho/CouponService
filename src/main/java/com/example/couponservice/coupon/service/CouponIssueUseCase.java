package com.example.couponservice.coupon.service;

import com.example.couponservice.coupon.entity.CouponIssue;

public interface CouponIssueUseCase {
    CouponIssue issueCoupon(
            Long couponId,
            Long userId
    );
}
