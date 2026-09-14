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

    // V3 조건부 Atomic UPDATE.
    // 재고가 남아 있고 발급 기간 안일 때만 1 감소시키며, 갱신된 행 수로 성공 여부를 판단한다.
    // clearAutomatically: UPDATE 뒤 영속성 컨텍스트를 비워, 이어지는 findById가 옛 값이 아닌 DB 최신 값을 읽게 한다.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Coupon coupon
            set coupon.remainingQuantity = coupon.remainingQuantity - 1
            where coupon.id = :couponId
              and coupon.remainingQuantity > 0
              and coupon.startAt <= :issuedAt
              and coupon.endAt >= :issuedAt
            """)
    int decreaseRemainingQuantityIfIssuable(
            @Param("couponId") Long couponId,
            @Param("issuedAt") LocalDateTime issuedAt
    );
}