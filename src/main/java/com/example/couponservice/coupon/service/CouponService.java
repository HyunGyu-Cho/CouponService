package com.example.couponservice.coupon.service;

import com.example.couponservice.coupon.entity.Coupon;
import com.example.couponservice.coupon.entity.CouponIssue;
import com.example.couponservice.coupon.exception.CouponErrorCode;
import com.example.couponservice.coupon.exception.CouponException;
import com.example.couponservice.coupon.repository.CouponIssueRepository;
import com.example.couponservice.coupon.repository.CouponRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CouponService {
    private final CouponRepository couponRepository;
    private final CouponIssueRepository couponIssueRepository;

    // 쿠폰 생성 함수
    @Transactional
    public Coupon createCoupon(
        String name,
        int totalQuantity,
        LocalDateTime startAt,
        LocalDateTime endAt
    ) {
        // 1. 요청값 받기
        // 2. Coupon.create()
        // 3. CouponRepository.save()
        // 4. 저장된 Coupon 반환

        Coupon coupon = Coupon.create(
                name,
                totalQuantity,
                startAt,
                endAt
        );

        return couponRepository.save(coupon);
    }

    // 쿠폰 단건 조회 함수
    @Transactional(readOnly = true)
    public Coupon getCoupon(
        Long couponId
    ) {
        return couponRepository.findById(couponId)
                .orElseThrow(() ->
                        new CouponException(CouponErrorCode.COUPON_NOT_FOUND)
                );
    }

    // 사용자별 보유 쿠폰 조회
    @Transactional(readOnly = true)
    public List<CouponIssue> getIssuedCouponsByUserId(
            Long userId
    ) {
        return couponIssueRepository.findAllWithCouponByUserId(userId);
    }

}
