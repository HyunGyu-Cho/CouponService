package com.example.couponservice.coupon.service.v4;

import com.example.couponservice.coupon.entity.Coupon;
import com.example.couponservice.coupon.entity.CouponIssue;
import com.example.couponservice.coupon.exception.CouponErrorCode;
import com.example.couponservice.coupon.exception.CouponException;
import com.example.couponservice.coupon.service.CouponIssueUseCase;
import com.example.couponservice.coupon.service.CouponService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V4 순차 시나리오. 재고와 중복 여부의 정본이 DB가 아니라 Redis이므로 확인 대상도 Redis다.
 * 뒤쪽 세 개는 설계에서 고친 두 지점(초기화 순서, 원인별 보상)의 회귀 테스트다.
 */
@SpringBootTest(properties = "coupon.issue.version=v4")
class V4CouponIssueServiceTest {

    private static final int TOTAL_QUANTITY = 10;

    @Autowired
    private CouponIssueUseCase couponIssueUseCase;

    @Autowired
    private CouponService couponService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final List<Long> createdCouponIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long couponId : createdCouponIds) {
            jdbcTemplate.update("delete from coupon_issue where coupon_id = ?", couponId);
            jdbcTemplate.update("delete from coupon where id = ?", couponId);
            redisTemplate.delete(List.of(
                    V4RedisKeys.toStockKey(couponId),
                    V4RedisKeys.toIssuedKey(couponId)
            ));
        }
    }

    @Test
    void issuesCouponAndDecreasesRedisStock() {
        Long couponId = newCoupon(TOTAL_QUANTITY);

        CouponIssue couponIssue = couponIssueUseCase.issueCoupon(couponId, 1L);

        assertThat(couponIssue.getUserId()).isEqualTo(1L);
        assertThat(stockOf(couponId)).isEqualTo(TOTAL_QUANTITY - 1);
        assertThat(issuedUsersOf(couponId)).containsExactly("1");
        assertThat(issueCountOf(couponId)).isEqualTo(1);
    }

    @Test
    void throwsDuplicateIssueWithoutTouchingStock() {
        Long couponId = newCoupon(TOTAL_QUANTITY);
        couponIssueUseCase.issueCoupon(couponId, 1L);

        assertCouponError(() -> couponIssueUseCase.issueCoupon(couponId, 1L),
                CouponErrorCode.DUPLICATE_ISSUE);

        assertThat(stockOf(couponId)).isEqualTo(TOTAL_QUANTITY - 1);
        assertThat(issueCountOf(couponId)).isEqualTo(1);
    }

    @Test
    void throwsSoldOutWhenStockIsZero() {
        Long couponId = newCoupon(1);
        couponIssueUseCase.issueCoupon(couponId, 1L);

        assertCouponError(() -> couponIssueUseCase.issueCoupon(couponId, 2L),
                CouponErrorCode.SOLD_OUT);

        assertThat(stockOf(couponId)).isZero();
        assertThat(issuedUsersOf(couponId)).containsExactly("1");
    }

    @Test
    void throwsNotStartedBeforePeriod() {
        LocalDateTime now = LocalDateTime.now();
        Long couponId = newCoupon(TOTAL_QUANTITY, now.plusHours(1), now.plusHours(2));

        assertCouponError(() -> couponIssueUseCase.issueCoupon(couponId, 1L),
                CouponErrorCode.NOT_STARTED);

        // 기간 검증은 Redis 앞에 있으므로 키가 만들어지지 않아야 한다.
        assertThat(redisTemplate.hasKey(V4RedisKeys.toStockKey(couponId))).isFalse();
    }

    @Test
    void throwsExpiredAfterPeriod() {
        LocalDateTime now = LocalDateTime.now();
        Long couponId = newCoupon(TOTAL_QUANTITY, now.minusHours(2), now.minusHours(1));

        assertCouponError(() -> couponIssueUseCase.issueCoupon(couponId, 1L),
                CouponErrorCode.EXPIRED);

        assertThat(redisTemplate.hasKey(V4RedisKeys.toStockKey(couponId))).isFalse();
    }

    @Test
    void throwsCouponNotFoundForUnknownCoupon() {
        assertCouponError(() -> couponIssueUseCase.issueCoupon(-1L, 1L),
                CouponErrorCode.COUPON_NOT_FOUND);
    }

    /**
     * 명단 복원의 회귀 테스트.
     * 초기화가 issued 를 복원하지 않으면 재고가 전체-1로 계산되고 명단에 기존 발급자가 없어 깨진다.
     */
    @Test
    void initializesStockAndIssuedUsersFromIssueHistory() {
        Long couponId = newCoupon(TOTAL_QUANTITY);
        insertIssueRow(couponId, 1L);
        insertIssueRow(couponId, 2L);

        couponIssueUseCase.issueCoupon(couponId, 3L);

        // 재고는 전체에서 기존 발급 2건을 뺀 뒤 이번 1건을 더 뺀 값이어야 한다.
        assertThat(stockOf(couponId)).isEqualTo(TOTAL_QUANTITY - 3);
        assertThat(issuedUsersOf(couponId)).containsExactlyInAnyOrder("1", "2", "3");
    }

    /**
     * 기존 발급자가 Redis 가 빈 상태에서 다시 요청해도 409 중복으로 끝나고 재고가 줄지 않는지 본다.
     * 초기화가 걸렀는지 DB UNIQUE 제약이 걸렀는지는 밖에서 구분되지 않으므로
     * 이 테스트는 초기화 순서의 회귀 테스트가 아니다. 순서는 동시 요청이 끼어드는 창에서만 드러난다.
     */
    @Test
    void throwsDuplicateIssueForUserIssuedBeforeInitialization() {
        Long couponId = newCoupon(TOTAL_QUANTITY);
        insertIssueRow(couponId, 1L);

        assertCouponError(() -> couponIssueUseCase.issueCoupon(couponId, 1L),
                CouponErrorCode.DUPLICATE_ISSUE);

        assertThat(stockOf(couponId)).isEqualTo(TOTAL_QUANTITY - 1);
        assertThat(issuedUsersOf(couponId)).containsExactly("1");
        assertThat(issueCountOf(couponId)).isEqualTo(1);
    }

    /**
     * 보상 분기의 회귀 테스트.
     * "명단이 DB보다 뒤처진" 상태를 손으로 만들어 Redis 승인 뒤 UNIQUE 위반을 일으킨다.
     * 재고는 되돌리되 명단에서는 지우지 않아야 한다. 지우면 재시도마다 다시 승인받고 다시 실패한다.
     */
    @Test
    void keepsIssuedUserAndRestoresStockOnUniqueViolation() {
        Long couponId = newCoupon(TOTAL_QUANTITY);
        insertIssueRow(couponId, 1L);

        // 초기화를 건너뛰게 stock 키만 만들고 명단은 비워 둔다.
        int stockBefore = TOTAL_QUANTITY - 1;
        redisTemplate.opsForValue().set(
                V4RedisKeys.toStockKey(couponId),
                String.valueOf(stockBefore)
        );

        assertCouponError(() -> couponIssueUseCase.issueCoupon(couponId, 1L),
                CouponErrorCode.DUPLICATE_ISSUE);

        assertThat(stockOf(couponId)).isEqualTo(stockBefore);
        assertThat(issuedUsersOf(couponId)).containsExactly("1");

        // 명단에 남아 있으므로 재시도는 DB까지 가지 않고 중복으로 끝난다.
        assertCouponError(() -> couponIssueUseCase.issueCoupon(couponId, 1L),
                CouponErrorCode.DUPLICATE_ISSUE);

        assertThat(stockOf(couponId)).isEqualTo(stockBefore);
        assertThat(issueCountOf(couponId)).isEqualTo(1);
    }

    private Long newCoupon(int totalQuantity) {
        LocalDateTime now = LocalDateTime.now();
        return newCoupon(totalQuantity, now.minusMinutes(1), now.plusHours(1));
    }

    private Long newCoupon(
            int totalQuantity,
            LocalDateTime startAt,
            LocalDateTime endAt
    ) {
        Coupon coupon = couponService.createCoupon("v4-test", totalQuantity, startAt, endAt);
        createdCouponIds.add(coupon.getId());
        return coupon.getId();
    }

    /**
     * 발급 서비스를 거치지 않고 발급 이력만 남긴다.
     * Redis가 비어 있는데 DB에는 이력이 있는 상태를 만들기 위한 것이다.
     */
    private void insertIssueRow(
            Long couponId,
            Long userId
    ) {
        jdbcTemplate.update(
                "insert into coupon_issue (coupon_id, user_id, issued_at) values (?, ?, ?)",
                couponId,
                userId,
                Timestamp.valueOf(LocalDateTime.now())
        );
    }

    private int stockOf(Long couponId) {
        String stock = redisTemplate.opsForValue()
                .get(V4RedisKeys.toStockKey(couponId));

        assertThat(stock).as("Redis 재고 키").isNotNull();

        return Integer.parseInt(stock);
    }

    private List<String> issuedUsersOf(Long couponId) {
        Set<String> issuedUsers = redisTemplate.opsForSet()
                .members(V4RedisKeys.toIssuedKey(couponId));

        return issuedUsers == null ? List.of() : List.copyOf(issuedUsers);
    }

    private int issueCountOf(Long couponId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from coupon_issue where coupon_id = ?", Integer.class, couponId);
    }

    private void assertCouponError(
            Runnable action,
            CouponErrorCode expected
    ) {
        assertThatThrownBy(action::run)
                .isInstanceOf(CouponException.class)
                .extracting(throwable -> ((CouponException) throwable).getErrorCode())
                .isEqualTo(expected);
    }
}
