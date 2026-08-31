package com.example.couponservice.coupon.dto.response;

import com.example.couponservice.coupon.entity.CouponIssue;

import java.time.LocalDateTime;

public record UserCouponResponse(
        Long issueId,
        Long couponId,
        String name,
        LocalDateTime expiresAt,
        LocalDateTime issuedAt
) {
    public static UserCouponResponse from(CouponIssue couponIssue) {
        return new UserCouponResponse(
                couponIssue.getId(),
                couponIssue.getCoupon().getId(),
                couponIssue.getCoupon().getName(),
                couponIssue.getCoupon().getEndAt(),
                couponIssue.getIssuedAt()
        );
    }
}
