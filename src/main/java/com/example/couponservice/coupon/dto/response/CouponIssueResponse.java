package com.example.couponservice.coupon.dto.response;

import com.example.couponservice.coupon.entity.CouponIssue;

import java.time.LocalDateTime;

public record CouponIssueResponse(
        Long issueId,
        Long couponId,
        Long userId,
        LocalDateTime issuedAt
) {

    public static CouponIssueResponse from(CouponIssue couponIssue) {
        return new CouponIssueResponse(
                couponIssue.getId(),
                couponIssue.getCoupon().getId(),
                couponIssue.getUserId(),
                couponIssue.getIssuedAt()
        );
    }
}
