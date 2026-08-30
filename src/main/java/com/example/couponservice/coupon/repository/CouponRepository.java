package com.example.couponservice.coupon.repository;

import com.example.couponservice.coupon.entity.Coupon;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponRepository extends JpaRepository<Coupon, Long> {
}
