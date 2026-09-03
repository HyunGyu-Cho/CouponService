package com.example.couponservice.coupon.repository;

import com.example.couponservice.coupon.entity.Coupon;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface CouponRepository extends JpaRepository<Coupon, Long> {

    // 비관적 락 이용
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select coupon
            from Coupon coupon
            where coupon.id = :couponId
            """)
    Optional<Coupon> findByIdForUpdate(
            @Param("couponId") Long couponId
    );

    // 조건부 Update
    @Modifying
    @Query("""
     update Coupon c
      set c.remainingQuantity = c.remainingQuantity -1 
      where c.id = :couponId
      and c.remainingQuantity > 0
      and c.startAt <= :issuedAt
      and c.endAt >= :isseudAt
    """)
    int decreaseRemainingQuantityIfIssuable(
            @Param("couponId") Long couponId,
            @Param("isseudAt")LocalDateTime issuedAt
    );
}