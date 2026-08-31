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

    @Transactional
    public Coupon createCoupon(
            String name,
            int totalQuantity,
            LocalDateTime startAt,
            LocalDateTime endAt
    ) {
        Coupon coupon = Coupon.create(
                name,
                totalQuantity,
                startAt,
                endAt
        );

        return couponRepository.save(coupon);
    }

    @Transactional(readOnly = true)
    public Coupon getCoupon(
            Long couponId
    ) {
        return couponRepository.findById(couponId)
                .orElseThrow(() ->
                        new CouponException(CouponErrorCode.COUPON_NOT_FOUND)
                );
    }

    @Transactional(readOnly = true)
    public List<CouponIssue> getIssuedCouponsByUserId(
            Long userId
    ) {
        return couponIssueRepository.findAllWithCouponByUserId(userId);
    }
}
