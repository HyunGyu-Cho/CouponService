package com.example.couponservice.coupon.service.v3;

import com.example.couponservice.coupon.service.CouponIssueConcurrencyTestBase;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * V3 조건부 Atomic UPDATE. V2와 같은 기준으로 정합성과 가용성을 확인한다.
 */
@SpringBootTest(properties = "coupon.issue.version=v3")
class V3CouponIssueConcurrencyTest extends CouponIssueConcurrencyTestBase {

    @Override
    protected boolean expectsNoUnexpectedFailure() {
        return true;
    }
}
