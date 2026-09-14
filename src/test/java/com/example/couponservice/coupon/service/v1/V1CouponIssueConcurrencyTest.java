package com.example.couponservice.coupon.service.v1;

import com.example.couponservice.coupon.service.CouponIssueConcurrencyTestBase;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * V1 기준선. 락이 없어 동시 요청 중 일부가 스냅샷 충돌로 실패하는 것이 문서화된 동작이므로
 * 정합성(정확히 100장, 잔여 0)만 확인한다.
 */
@SpringBootTest(properties = "coupon.issue.version=v1")
class V1CouponIssueConcurrencyTest extends CouponIssueConcurrencyTestBase {

    @Override
    protected boolean expectsNoUnexpectedFailure() {
        return false;
    }
}
