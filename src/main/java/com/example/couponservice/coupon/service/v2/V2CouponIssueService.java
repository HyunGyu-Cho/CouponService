package com.example.couponservice.coupon.service.v2;

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
        havingValue = "v2"
)
public class V2CouponIssueService implements CouponIssueUseCase {
    private final CouponRepository couponRepository;
    private final CouponIssueRepository couponIssueRepository;

    @Override
    @Transactional
    public CouponIssue issueCoupon(
            Long couponId,
            Long userId
    ) {
        // couponId 에 해당하는 coupon을 가져온다 select ... for update로
        Coupon coupon = getCouponByIdForUpdate(couponId);

        // 이미 발급받은 쿠폰인지 확인한다
        validateNotAlreadyIssued(couponId, userId);

        // 발급시각은 현재 시간으로 한다
        LocalDateTime issuedAt = LocalDateTime.now();

        // 발급 가능 여부를 검증한 뒤 잔여 수량을 하나 감소시킨다
        coupon.issue(issuedAt);

        // CouponIssue 만들고
        CouponIssue couponIssue = CouponIssue.create(
                coupon,
                userId,
                issuedAt
        );

        // coupon_issue db에 저장한다
        return couponIssueRepository.save(couponIssue);
    }

    private Coupon getCouponByIdForUpdate(Long couponId) {
        return couponRepository.findByIdForUpdate(couponId)
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

        if(alreadyIssued) {
            throw new CouponException(CouponErrorCode.DUPLICATE_ISSUE);
        }
    }
}
