package com.example.couponservice.coupon.controller;

import com.example.couponservice.coupon.dto.response.UserCouponListResponse;
import com.example.couponservice.coupon.dto.response.UserCouponResponse;
import com.example.couponservice.coupon.entity.CouponIssue;
import com.example.couponservice.coupon.service.CouponService;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/users")
public class UserCouponController {
    private final CouponService couponService;

    @GetMapping("/{userId}/coupons")
    public ResponseEntity<UserCouponListResponse> getIssuedCouponsByUserId(
            @PathVariable @Positive Long userId
    ) {
        List<CouponIssue> couponIssues =
                couponService.getIssuedCouponsByUserId(userId);

        // 목록을 먼저 완성한 후 응답 객체 만드는 것이 좋다
        List<UserCouponResponse> coupons = couponIssues.stream()
                .map(UserCouponResponse::from)
                .toList();

        UserCouponListResponse response = new UserCouponListResponse(
                // 생성자 어떻게..?
                userId,
                coupons
        );

        return ResponseEntity
                .status(HttpStatus.OK)
                .body(response);
    }
}
