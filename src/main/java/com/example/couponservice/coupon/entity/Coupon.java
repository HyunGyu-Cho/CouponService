package com.example.couponservice.coupon.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "coupon")
public class Coupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "total_quantity", nullable = false)
    private int totalQuantity;

    @Column(name = "remaining_quantity", nullable = false)
    private int remainingQuantity;

    @Column(name = "start_at", nullable = false)
    private LocalDateTime startAt;

    @Column(name = "end_at", nullable = false)
    private LocalDateTime endAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // 검증된 값으로 쿠폰 엔티티를 초기화한다.
    private Coupon(
            String name,
            int totalQuantity,
            LocalDateTime startAt,
            LocalDateTime endAt
    ) {
        this.name = name;
        this.totalQuantity = totalQuantity;
        this.remainingQuantity = totalQuantity;
        this.startAt = startAt;
        this.endAt = endAt;
        this.createdAt = LocalDateTime.now();
    }

    // 입력값을 검증하고 새로운 쿠폰을 생성한다.
    public static Coupon create(
            String name,
            int totalQuantity,
            LocalDateTime startAt,
            LocalDateTime endAt
    ) {
        validateName(name);
        validateTotalQuantity(totalQuantity);
        validatePeriod(startAt, endAt);

        return new Coupon(name, totalQuantity, startAt, endAt);
    }

    // 발급 가능 여부를 검증하고 잔여 수량을 1개 감소시킨다.
    public void issue(LocalDateTime issuedAt) {
        validateIssuedAt(issuedAt);
        validateIssuablePeriod(issuedAt);
        validateRemainingQuantity();

        remainingQuantity--;
    }

    // 쿠폰 이름이 비어 있는지 검증한다.
    private static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("쿠폰 이름은 비어 있을 수 없습니다.");
        }
    }

    // 쿠폰 전체 수량이 1개 이상인지 검증한다.
    private static void validateTotalQuantity(int totalQuantity) {
        if (totalQuantity <= 0) {
            throw new IllegalArgumentException("쿠폰 전체 수량은 1개 이상이어야 합니다.");
        }
    }

    // 쿠폰 발급 시작 시간이 종료 시간보다 이전인지 검증한다.
    private static void validatePeriod(
            LocalDateTime startAt,
            LocalDateTime endAt
    ) {
        if (startAt == null || endAt == null) {
            throw new IllegalArgumentException("쿠폰 발급 시작 시간과 종료 시간은 필수입니다.");
        }

        if (!startAt.isBefore(endAt)) {
            throw new IllegalArgumentException("쿠폰 발급 시작 시간은 종료 시간보다 이전이어야 합니다.");
        }
    }

    // 쿠폰 발급 시간이 입력되었는지 검증한다.
    private static void validateIssuedAt(LocalDateTime issuedAt) {
        if (issuedAt == null) {
            throw new IllegalArgumentException("쿠폰 발급 시간은 필수입니다.");
        }
    }

    // 요청한 시간이 쿠폰 발급 가능 기간에 포함되는지 검증한다.
    private void validateIssuablePeriod(LocalDateTime issuedAt) {
        if (issuedAt.isBefore(startAt)) {
            throw new IllegalStateException("쿠폰 발급 기간이 시작되지 않았습니다.");
        }

        if (issuedAt.isAfter(endAt)) {
            throw new IllegalStateException("쿠폰 발급 기간이 종료되었습니다.");
        }
    }

    // 발급할 수 있는 쿠폰 재고가 남아 있는지 검증한다.
    private void validateRemainingQuantity() {
        if (remainingQuantity <= 0) {
            throw new IllegalStateException("쿠폰 재고가 모두 소진되었습니다.");
        }
    }
}
