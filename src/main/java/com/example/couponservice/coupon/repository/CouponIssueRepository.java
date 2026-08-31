package com.example.couponservice.coupon.repository;

import com.example.couponservice.coupon.entity.CouponIssue;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CouponIssueRepository extends JpaRepository<CouponIssue, Long> {

    boolean existsByCoupon_IdAndUserId(
            Long couponId,
            Long userId
    );

    @Query("""
            select couponIssue
            from CouponIssue couponIssue
            join fetch couponIssue.coupon
            where couponIssue.userId = :userId
            """)
    List<CouponIssue> findAllWithCouponByUserId(
            @Param("userId") Long userId
    );
}
