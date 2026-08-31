package com.example.couponservice.coupon.dto.response;

import com.example.couponservice.coupon.entity.Coupon;

import java.time.LocalDateTime;

public record CouponResponse(
        Long couponId,
        String name,
        int totalQuantity,
        int remainingQuantity,
        LocalDateTime startAt,
        LocalDateTime endAt,
        LocalDateTime createdAt
) {
    public static CouponResponse from(Coupon coupon) {
        return new CouponResponse(
                coupon.getId(),
                coupon.getName(),
                coupon.getTotalQuantity(),
                coupon.getRemainingQuantity(),
                coupon.getStartAt(),
                coupon.getEndAt(),
                coupon.getCreatedAt()
        );
    }
}
