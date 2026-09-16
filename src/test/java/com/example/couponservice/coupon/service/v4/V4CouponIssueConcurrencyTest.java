package com.example.couponservice.coupon.service.v4;

import com.example.couponservice.coupon.service.CouponIssueConcurrencyTestBase;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V4 Redis 원자 연산. 정합성과 가용성의 기준은 V2·V3와 같고, 잔여 수량의 정본이 Redis라 읽는 곳만 다르다.
 * 300개 요청이 비어 있는 Redis에 동시에 도착하므로 여러 스레드가 동시에 지연 초기화를 시도한다.
 * 그래도 정확히 100장만 나가야 한다.
 */
@SpringBootTest(properties = "coupon.issue.version=v4")
class V4CouponIssueConcurrencyTest extends CouponIssueConcurrencyTestBase {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Override
    protected boolean expectsNoUnexpectedFailure() {
        return true;
    }

    @Override
    protected int getRemainingQuantity(Long couponId) {
        String stock = redisTemplate.opsForValue()
                .get(V4RedisKeys.toStockKey(couponId));

        assertThat(stock)
                .as("V4는 Redis 재고가 정본이므로 요청 뒤에는 키가 있어야 한다")
                .isNotNull();

        return Integer.parseInt(stock);
    }

    @Override
    protected void cleanUpVersionState(Long couponId) {
        redisTemplate.delete(List.of(
                V4RedisKeys.toStockKey(couponId),
                V4RedisKeys.toIssuedKey(couponId)
        ));
    }
}
