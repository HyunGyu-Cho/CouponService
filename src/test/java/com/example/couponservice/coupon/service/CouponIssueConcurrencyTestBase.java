package com.example.couponservice.coupon.service;

import com.example.couponservice.coupon.entity.Coupon;
import com.example.couponservice.coupon.exception.CouponErrorCode;
import com.example.couponservice.coupon.exception.CouponException;
import com.example.couponservice.global.exception.ErrorCode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 발급 구현 버전마다 같은 동시성 시나리오를 돌리는 공통 본체.
 * 하위 클래스가 {@code coupon.issue.version} 설정으로 구현체를 고른다.
 *
 * <p>모든 버전이 지켜야 하는 불변식:
 * <pre>
 * issue_count <= total_quantity
 * issue_count == total_quantity - remaining_quantity
 * remaining_quantity >= 0
 * </pre>
 *
 * <p>가용성(정확히 전체 수량 발급, 예상 밖 예외 0건)은 버전마다 기대가 달라
 * {@link #expectsNoUnexpectedFailure()}로 나눈다. V1은 락이 없어 스냅샷 충돌로 다수 요청이 실패하는 것이
 * 문서화된 기준선이므로 불변식만 확인한다.
 */
public abstract class CouponIssueConcurrencyTestBase {

    private static final int TOTAL_QUANTITY = 100;
    private static final int REQUESTS = 300;
    private static final int THREADS = 32;

    @Autowired
    private CouponIssueUseCase couponIssueUseCase;

    @Autowired
    private CouponService couponService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long couponId;

    protected abstract boolean expectsNoUnexpectedFailure();

    @BeforeEach
    void createCoupon() {
        LocalDateTime now = LocalDateTime.now();
        Coupon coupon = couponService.createCoupon(
                "concurrency-test",
                TOTAL_QUANTITY,
                now.minusMinutes(1),
                now.plusHours(1)
        );
        couponId = coupon.getId();
    }

    @AfterEach
    void deleteCoupon() {
        jdbcTemplate.update("delete from coupon_issue where coupon_id = ?", couponId);
        jdbcTemplate.update("delete from coupon where id = ?", couponId);
        cleanUpVersionState(couponId);
    }

    /**
     * 실시간 잔여 수량을 읽는다. V1~V3는 DB의 remaining_quantity가 정본이지만
     * V4처럼 재고를 DB 밖에서 관리하는 구현은 읽을 곳이 다르므로 하위 클래스가 바꾼다.
     */
    protected int getRemainingQuantity(Long couponId) {
        return jdbcTemplate.queryForObject(
                "select remaining_quantity from coupon where id = ?", Integer.class, couponId);
    }

    /**
     * DB 밖에 남는 버전별 상태를 정리한다. 남는 것이 없는 버전은 그대로 둔다.
     */
    protected void cleanUpVersionState(Long couponId) {
    }

    @Test
    void issuesExactlyTotalQuantityUnderConcurrentRequests() throws InterruptedException {
        AtomicInteger created = new AtomicInteger();
        Map<ErrorCode, AtomicInteger> businessErrors = new ConcurrentHashMap<>();
        List<Throwable> unexpected = new ArrayList<>();

        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(REQUESTS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(REQUESTS);

        for (int i = 1; i <= REQUESTS; i++) {
            long userId = i;
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    couponIssueUseCase.issueCoupon(couponId, userId);
                    created.incrementAndGet();
                } catch (CouponException e) {
                    businessErrors
                            .computeIfAbsent(e.getErrorCode(), k -> new AtomicInteger())
                            .incrementAndGet();
                } catch (Throwable t) {
                    synchronized (unexpected) {
                        unexpected.add(t);
                    }
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await(10, TimeUnit.SECONDS);
        start.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).as("60초 안에 모든 요청 완료").isTrue();
        executor.shutdown();

        Integer issueCount = jdbcTemplate.queryForObject(
                "select count(*) from coupon_issue where coupon_id = ?", Integer.class, couponId);
        int remaining = getRemainingQuantity(couponId);

        String detail = "성공=" + created.get()
                + ", 비즈니스오류=" + businessErrors
                + ", 예상밖예외=" + summarize(unexpected);

        // 불변식: 모든 버전 공통
        assertThat(issueCount).as("발급 수 <= 전체 수량. " + detail).isLessThanOrEqualTo(TOTAL_QUANTITY);
        assertThat(remaining).as("잔여 수량 >= 0. " + detail).isGreaterThanOrEqualTo(0);
        assertThat(issueCount).as("발급 수 == 전체 - 잔여. " + detail).isEqualTo(TOTAL_QUANTITY - remaining);
        assertThat(created.get()).as("성공 응답 수 == 발급 이력 수. " + detail).isEqualTo(issueCount);

        // 가용성: 락 또는 원자적 갱신을 도입한 버전은 정확히 전체 수량을 발급하고 예상 밖 예외가 없어야 한다
        if (expectsNoUnexpectedFailure()) {
            assertThat(unexpected)
                    .as("예상 밖 예외. " + detail)
                    .isEmpty();
            assertThat(issueCount).as("발급 이력 수. " + detail).isEqualTo(TOTAL_QUANTITY);
            assertThat(remaining).as("잔여 수량. " + detail).isZero();
            assertThat(businessErrors.keySet())
                    .as("비즈니스 오류는 매진뿐이어야 한다")
                    .containsOnly(CouponErrorCode.SOLD_OUT);
            assertThat(businessErrors.get(CouponErrorCode.SOLD_OUT).get())
                    .isEqualTo(REQUESTS - TOTAL_QUANTITY);
        }
    }

    private static String summarize(List<Throwable> throwables) {
        Map<String, Integer> counts = new ConcurrentHashMap<>();
        for (Throwable t : throwables) {
            counts.merge(t.getClass().getSimpleName(), 1, Integer::sum);
        }
        return counts.toString();
    }
}
