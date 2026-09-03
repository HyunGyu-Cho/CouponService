package com.example.couponservice.coupon.service.v1;

import com.example.couponservice.coupon.entity.Coupon;
import com.example.couponservice.coupon.entity.CouponIssue;
import com.example.couponservice.coupon.exception.CouponErrorCode;
import com.example.couponservice.coupon.exception.CouponException;
import com.example.couponservice.coupon.repository.CouponIssueRepository;
import com.example.couponservice.coupon.repository.CouponRepository;
import com.example.couponservice.coupon.service.CouponIssueUseCase;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "coupon.issue",
        name = "version",
        havingValue = "v1"
)
public class V1CouponIssueService implements CouponIssueUseCase {

    private final CouponRepository couponRepository;
    private final CouponIssueRepository couponIssueRepository;

    @Override
    @Transactional
    public CouponIssue issueCoupon(
            Long couponId,
            Long userId
    ) {
        Coupon coupon = getCouponById(couponId);
        validateNotAlreadyIssued(couponId, userId);

        LocalDateTime issuedAt = LocalDateTime.now();
        coupon.validateIssuable(issuedAt);

        CouponIssue couponIssue = CouponIssue.create(
                coupon,
                userId,
                issuedAt
        );

        return couponIssueRepository.save(couponIssue);
    }

    private Coupon getCouponById(Long couponId) {
        return couponRepository.findById(couponId)
                .orElseThrow(() ->
                        new CouponException(CouponErrorCode.COUPON_NOT_FOUND)
                );
    }

    private void validateNotAlreadyIssued(
            Long couponId,
            Long userId
    ) {
        boolean alreadyIssued = couponIssueRepository.existsByCoupon_IdAndUserId(
                couponId,
                userId
        );

        if (alreadyIssued) {
            throw new CouponException(CouponErrorCode.DUPLICATE_ISSUE);
        }
    }
}
