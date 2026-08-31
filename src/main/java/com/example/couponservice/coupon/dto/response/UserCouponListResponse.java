package com.example.couponservice.coupon.dto.response;

import java.util.List;

public record UserCouponListResponse(
        Long userId,
        List<UserCouponResponse> coupons
) {
}
