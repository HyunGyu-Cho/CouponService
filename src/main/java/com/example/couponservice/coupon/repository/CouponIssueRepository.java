package com.example.couponservice.coupon.repository;

import com.example.couponservice.coupon.entity.CouponIssue;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CouponIssueRepository extends JpaRepository<CouponIssue, Long> {

    // 동일 사용자에게 이미 쿠폰이 발급되었는지 확인
    boolean existsByCoupon_IdAndUserId(
            Long couponId,
            Long userId
    );

    // 사용자별 발급 이력 조회
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
