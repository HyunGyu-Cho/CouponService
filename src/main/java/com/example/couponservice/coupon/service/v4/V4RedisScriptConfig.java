package com.example.couponservice.coupon.service.v4;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * V4 발급 Lua 스크립트를 빈으로 올린다.
 * 스크립트는 V4 전용이므로 전역 설정이 아니라 이 패키지에 둔다.
 */
@Configuration
@ConditionalOnProperty(
        prefix = "coupon.issue",
        name = "version",
        havingValue = "v4"
)
public class V4RedisScriptConfig {

    @Bean
    public RedisScript<Long> issueCouponScript() {
        DefaultRedisScript<Long> issueCouponScript = new DefaultRedisScript<>();
        issueCouponScript.setLocation(
                new ClassPathResource("redis/issue-coupon.lua")
        );
        issueCouponScript.setResultType(Long.class);

        return issueCouponScript;
    }
}
