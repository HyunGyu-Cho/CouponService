package com.example.couponservice.coupon.service.v3;

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
        havingValue = "v3"
)
public class V3CouponIssueService implements CouponIssueUseCase {

    private final CouponRepository couponRepository;
    private final CouponIssueRepository couponIssueRepository;

    @Override
    @Transactional
    public CouponIssue issueCoupon(
            Long couponId,
            Long userId
    ) {
        LocalDateTime issuedAt = LocalDateTime.now();

        decreaseRemainingQuantity(couponId, issuedAt);
        validateNotAlreadyIssued(couponId, userId);

        Coupon couponReference = couponRepository.getReferenceById(couponId);

        CouponIssue couponIssue = CouponIssue.create(
                couponReference,
                userId,
                issuedAt
        );

        return couponIssueRepository.save(couponIssue);
    }

    private void decreaseRemainingQuantity(
            Long couponId,
            LocalDateTime issuedAt
    ) {
        int affectedRows =
                couponRepository.decreaseRemainingQuantityIfIssuable(
                        couponId,
                        issuedAt
                );

        if (affectedRows == 0) {
            throwIssueFailure(couponId, issuedAt);
        }
    }

    private void throwIssueFailure(
            Long couponId,
            LocalDateTime issuedAt
    ) {
        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() ->
                        new CouponException(
                                CouponErrorCode.COUPON_NOT_FOUND
                        )
                );

        coupon.validateIssuable(issuedAt);

        throw new IllegalStateException(
                "조건부 쿠폰 재고 감소 실패 원인을 확인할 수 없습니다."
        );
    }

    private void validateNotAlreadyIssued(
            Long couponId,
            Long userId
    ) {
        boolean alreadyIssued =
                couponIssueRepository.existsByCoupon_IdAndUserId(
                        couponId,
                        userId
                );

        if (alreadyIssued) {
            throw new CouponException(
                    CouponErrorCode.DUPLICATE_ISSUE
            );
        }
    }
}