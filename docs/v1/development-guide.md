# V1 Development Guide

이 문서는 V1 동기 발급에만 해당하는 범위와 발급 흐름을 설명한다.
계층 책임, 도메인 규칙, Validation, 예외 처리 같은 공통 규칙은 [공통 개발 가이드](../development-guide.md)를 따른다.

## V1 범위

- Spring MVC, Spring Data JPA, MariaDB를 사용하는 가장 단순한 동기 발급을 구현한다.
- V1 발급 구현은 `coupon.service.v1.V1CouponIssueService`에 둔다.
- V1에서는 `@Lock`, 비관적 락, 낙관적 락, 원자적 UPDATE를 추가하지 않는다.
- V1의 목적은 락 없는 발급 흐름에서 동시성 문제를 재현해 다음 단계의 근거를 만드는 것이다.

## 발급 흐름

```text
1. Coupon 일반 조회
2. 동일 couponId, userId 발급 이력 존재 여부 확인
3. 발급 시각 생성
4. Coupon.issue(issuedAt) 호출: 기간과 재고 검증 후 잔여 수량 1 감소
5. CouponIssue.create(coupon, userId, issuedAt) 호출
6. CouponIssue 저장
```

- 1번 조회는 의도적으로 락 없이 수행한다. 여러 트랜잭션이 같은 쿠폰을 동시에 읽고 갱신할 때 무엇이 깨지는지 관찰하기 위해서다.
- 2번 중복 사전 조회를 동시에 통과한 요청은 DB UNIQUE 제약이 최종 차단한다.

## Repository

V1 시점의 Repository는 다음 메서드만 가진다. 이후 버전에서 추가된 메서드는 각 버전 문서에서 설명한다.

```java
public interface CouponRepository extends JpaRepository<Coupon, Long> {
}
```

```java
public interface CouponIssueRepository extends JpaRepository<CouponIssue, Long> {
    boolean existsByCoupon_IdAndUserId(Long couponId, Long userId);
}
```

## 관찰 대상

- 재고 100개에 1,000명이 동시에 요청할 때 정확히 100장만 발급되는가
- 초과 발급이나 음수 재고가 발생하는가
- 어떤 예외가 어느 단계에서 발생하며 HTTP 응답은 무엇인가
- TPS, 평균 응답시간, p95는 어느 수준인가

실험 결과는 [V1 부하 테스트 결과](load-test-result.md)에 기록했다.
