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
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * V3: 조건부 Atomic UPDATE 발급.
 * SELECT FOR UPDATE 없이 "재고 > 0 이면 1 감소" 를 UPDATE 한 번으로 처리해 락 보유 구간을 줄인다.
 *
 * <pre>
 * 쿠폰 존재 확인
 * -> 중복 발급 확인 (coupon_issue 조회)
 * -> 조건부 UPDATE (갱신 행 수 0이면 재고를 바꾸지 않고 원인만 판별)
 * -> CouponIssue 저장 (Coupon은 getReferenceById 참조)
 * </pre>
 *
 * 중복 확인을 UPDATE보다 먼저 두는 이유: 이미 받은 사용자의 요청이 쿠폰 행을 갱신했다가 롤백하는
 * 불필요한 행 경합을 피하기 위해서다. 동시에 통과한 중복 요청은 DB UNIQUE 제약이 최종 차단한다.
 *
 * 격리 수준을 READ COMMITTED로 낮추는 이유: MariaDB의 REPEATABLE READ 스냅샷 격리에서는
 * 트랜잭션 안의 첫 SELECT(쿠폰 존재 확인)가 스냅샷을 만들고, 그 뒤 다른 트랜잭션이 쿠폰 행을
 * 갱신하면 이 트랜잭션의 조건부 UPDATE가 "읽은 뒤 바뀐 행"으로 거부된다
 * (Record has changed since last read in table 'coupon', 동시성 테스트 300건 중 265건 실패).
 * V3의 정합성은 스냅샷이 아니라 조건부 UPDATE(재고)와 UNIQUE 제약(중복)이 보장하므로
 * REPEATABLE READ가 필요 없다. 근거와 재현 과정은 docs/v3/development-guide.md에 있다.
 */
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
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CouponIssue issueCoupon(
            Long couponId,
            Long userId
    ) {
        validateCouponExists(couponId);
        validateNotAlreadyIssued(couponId, userId);

        LocalDateTime issuedAt = LocalDateTime.now();
        decreaseRemainingQuantity(couponId, issuedAt);

        Coupon couponReference = couponRepository.getReferenceById(couponId);
        CouponIssue couponIssue = CouponIssue.create(
                couponReference,
                userId,
                issuedAt
        );

        return couponIssueRepository.save(couponIssue);
    }

    private void validateCouponExists(Long couponId) {
        if (!couponRepository.existsById(couponId)) {
            throw new CouponException(CouponErrorCode.COUPON_NOT_FOUND);
        }
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
            throw new CouponException(CouponErrorCode.DUPLICATE_ISSUE);
        }
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

    /**
     * 조건부 UPDATE가 0건일 때 어떤 조건에 걸렸는지 판별한다.
     * validateIssuable()은 상태를 바꾸지 않으므로 재고가 이중 감소하지 않는다.
     */
    private void throwIssueFailure(
            Long couponId,
            LocalDateTime issuedAt
    ) {
        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() ->
                        new CouponException(CouponErrorCode.COUPON_NOT_FOUND)
                );

        coupon.validateIssuable(issuedAt);

        throw new IllegalStateException(
                "조건부 쿠폰 재고 감소 실패 원인을 확인할 수 없습니다. couponId=" + couponId
        );
    }
}
