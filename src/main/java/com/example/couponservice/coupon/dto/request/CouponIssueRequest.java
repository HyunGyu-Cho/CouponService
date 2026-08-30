package com.example.couponservice.coupon.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CouponIssueRequest(
        @NotNull
        @Positive
        Long userId
) {
}
