package com.example.couponservice.coupon.repository;

import com.example.couponservice.coupon.entity.CouponIssue;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponIssueRepository extends JpaRepository<CouponIssue, Long> {

    boolean existsByCoupon_IdAndUserId(
            Long couponId,
            Long userId
    );
}
