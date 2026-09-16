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

    // V4 전용. Redis 초기화가 이미 발급받은 사용자 집합을 복원할 때 쓴다.
    // 엔티티가 아니라 userId만 읽어 초기화 비용을 줄인다.
    @Query("""
            select couponIssue.userId
            from CouponIssue couponIssue
            where couponIssue.coupon.id = :couponId
            """)
    List<Long> findUserIdsByCouponId(
            @Param("couponId") Long couponId
    );
}
