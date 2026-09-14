package com.example.couponservice.coupon.service.v3;

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
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V3 순차 시나리오. 조건부 UPDATE가 0건일 때 재고를 바꾸지 않고 정확한 오류 코드를 돌려주는지 확인한다.
 */
@SpringBootTest(properties = "coupon.issue.version=v3")
class V3CouponIssueServiceTest {

    @Autowired
    private CouponIssueUseCase couponIssueUseCase;

    @Autowired
    private CouponService couponService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> createdCouponIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long id : createdCouponIds) {
            jdbcTemplate.update("delete from coupon_issue where coupon_id = ?", id);
            jdbcTemplate.update("delete from coupon where id = ?", id);
        }
    }

    private Long newCoupon(int quantity, LocalDateTime startAt, LocalDateTime endAt) {
        Coupon coupon = couponService.createCoupon("v3-test", quantity, startAt, endAt);
        createdCouponIds.add(coupon.getId());
        return coupon.getId();
    }

    private int remainingOf(Long couponId) {
        return jdbcTemplate.queryForObject(
                "select remaining_quantity from coupon where id = ?", Integer.class, couponId);
    }

    @Test
    void issuesCouponAndDecreasesRemainingQuantity() {
        LocalDateTime now = LocalDateTime.now();
        Long couponId = newCoupon(2, now.minusMinutes(1), now.plusHours(1));

        CouponIssue issue = couponIssueUseCase.issueCoupon(couponId, 1L);

        assertThat(issue.getId()).isNotNull();
        assertThat(issue.getUserId()).isEqualTo(1L);
        assertThat(remainingOf(couponId)).isEqualTo(1);
    }

    @Test
    void throwsDuplicateIssueWithoutTouchingRemainingQuantity() {
        LocalDateTime now = LocalDateTime.now();
        Long couponId = newCoupon(2, now.minusMinutes(1), now.plusHours(1));
        couponIssueUseCase.issueCoupon(couponId, 1L);

        assertThatThrownBy(() -> couponIssueUseCase.issueCoupon(couponId, 1L))
                .isInstanceOfSatisfying(CouponException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CouponErrorCode.DUPLICATE_ISSUE));

        assertThat(remainingOf(couponId)).isEqualTo(1);
    }

    @Test
    void throwsSoldOutAndKeepsRemainingQuantityAtZero() {
        LocalDateTime now = LocalDateTime.now();
        Long couponId = newCoupon(1, now.minusMinutes(1), now.plusHours(1));
        couponIssueUseCase.issueCoupon(couponId, 1L);

        assertThatThrownBy(() -> couponIssueUseCase.issueCoupon(couponId, 2L))
                .isInstanceOfSatisfying(CouponException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CouponErrorCode.SOLD_OUT));

        assertThat(remainingOf(couponId)).isZero();
    }

    @Test
    void throwsNotStartedBeforePeriod() {
        LocalDateTime now = LocalDateTime.now();
        Long couponId = newCoupon(1, now.plusHours(1), now.plusHours(2));

        assertThatThrownBy(() -> couponIssueUseCase.issueCoupon(couponId, 1L))
                .isInstanceOfSatisfying(CouponException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CouponErrorCode.NOT_STARTED));

        assertThat(remainingOf(couponId)).isEqualTo(1);
    }

    @Test
    void throwsExpiredAfterPeriod() {
        LocalDateTime now = LocalDateTime.now();
        Long couponId = newCoupon(1, now.minusHours(2), now.minusHours(1));

        assertThatThrownBy(() -> couponIssueUseCase.issueCoupon(couponId, 1L))
                .isInstanceOfSatisfying(CouponException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CouponErrorCode.EXPIRED));

        assertThat(remainingOf(couponId)).isEqualTo(1);
    }

    @Test
    void throwsCouponNotFoundForUnknownCoupon() {
        assertThatThrownBy(() -> couponIssueUseCase.issueCoupon(999_999_999L, 1L))
                .isInstanceOfSatisfying(CouponException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CouponErrorCode.COUPON_NOT_FOUND));
    }
}
