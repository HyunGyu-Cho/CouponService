package com.example.couponservice.coupon.service.v4;

/**
 * V4가 쓰는 Redis 키 이름.
 * 발급 서비스와 테스트가 같은 규칙을 보도록 한 곳에 모은다.
 */
final class V4RedisKeys {

    private static final String STOCK_KEY_FORMAT = "coupon:%d:stock";
    private static final String ISSUED_KEY_FORMAT = "coupon:%d:issued";

    private V4RedisKeys() {
    }

    static String toStockKey(Long couponId) {
        return STOCK_KEY_FORMAT.formatted(couponId);
    }

    static String toIssuedKey(Long couponId) {
        return ISSUED_KEY_FORMAT.formatted(couponId);
    }
}
