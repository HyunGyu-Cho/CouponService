package com.example.couponservice.coupon.controller;

import com.example.couponservice.coupon.dto.request.CouponCreateRequest;
import com.example.couponservice.coupon.dto.response.CouponResponse;
import com.example.couponservice.coupon.entity.Coupon;
import com.example.couponservice.coupon.service.CouponService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/coupons")
public class CouponController {

    private final CouponService couponService;

    @PostMapping
    public ResponseEntity<CouponResponse> createCoupon(
            @Valid @RequestBody CouponCreateRequest request
    ) {
        Coupon coupon = couponService.createCoupon(
                request.name(),
                request.totalQuantity(),
                request.startAt(),
                request.endAt()
        );

        CouponResponse response = CouponResponse.from(coupon);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @GetMapping("/{couponId}")
    public ResponseEntity<CouponResponse> getCoupon(
            @PathVariable @Positive Long couponId
    ) {
        Coupon coupon = couponService.getCoupon(couponId);
        CouponResponse response = CouponResponse.from(coupon);

        return ResponseEntity.ok(response);
    }
}
