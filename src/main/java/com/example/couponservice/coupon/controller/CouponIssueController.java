package com.example.couponservice.coupon.controller;

import com.example.couponservice.coupon.dto.request.CouponIssueRequest;
import com.example.couponservice.coupon.dto.response.CouponIssueResponse;
import com.example.couponservice.coupon.entity.CouponIssue;
import com.example.couponservice.coupon.service.CouponIssueUseCase;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/coupons")
public class CouponIssueController {

    private final CouponIssueUseCase couponIssueUseCase;

    @PostMapping("/{couponId}/issue")
    public ResponseEntity<CouponIssueResponse> issueCoupon(
            @PathVariable @Positive Long couponId,
            @Valid @RequestBody CouponIssueRequest request
    ) {
        CouponIssue couponIssue = couponIssueUseCase.issueCoupon(
                couponId,
                request.userId()
        );

        CouponIssueResponse response =
                CouponIssueResponse.from(couponIssue);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }
}
