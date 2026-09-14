package com.example.couponservice.coupon.service.v2;

import com.example.couponservice.coupon.service.CouponIssueConcurrencyTestBase;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * V2 비관적 락. 정합성과 함께 예상 밖 예외가 없고 초과 요청은 전부 SOLD_OUT 인지 확인한다.
 */
@SpringBootTest(properties = "coupon.issue.version=v2")
class V2CouponIssueConcurrencyTest extends CouponIssueConcurrencyTestBase {

    @Override
    protected boolean expectsNoUnexpectedFailure() {
        return true;
    }
}
