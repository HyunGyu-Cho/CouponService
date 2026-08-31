# V2 Pessimistic Lock Development Guide

이 문서는 V1 쿠폰 발급 흐름에 비관적 쓰기 락을 적용한 V2의 설계와 구현 원칙을 설명한다.
외부 API와 데이터베이스 스키마는 V1과 동일하게 유지한다.

## V2 도입 배경

V1 부하 테스트에서는 동일한 쿠폰에 동시 발급 요청이 들어왔을 때 MariaDB
`ER_CHECKREAD 1020`과 Hibernate `SnapshotIsolationException`이 발생했다.
재고와 발급 수의 불변식은 유지됐지만 다수의 요청이 HTTP 500으로 종료됐다.

V2의 목표는 쿠폰 발급 트랜잭션을 쿠폰 행 단위로 직렬화해 다음 상태를 만드는 것이다.

```text
동일 couponId의 발급 요청
-> 한 트랜잭션만 쿠폰 행 잠금 획득
-> 나머지 요청은 잠금 해제를 기다림
-> 앞선 트랜잭션 커밋 후 최신 재고로 다음 요청 처리
```

## 발급 흐름

V1과 V2의 비즈니스 흐름은 동일하며 첫 쿠폰 조회 방식만 다르다.

```text
V1
Coupon 일반 조회
-> 중복 발급 확인
-> 발급 기간 및 재고 검증
-> 재고 감소
-> 발급 이력 저장
-> 커밋

V2
Coupon PESSIMISTIC_WRITE 조회
-> coupon 행 잠금
-> 중복 발급 확인
-> 발급 기간 및 재고 검증
-> 재고 감소
-> 발급 이력 저장
-> 커밋과 함께 잠금 해제
```

동일한 쿠폰을 발급하는 요청은 직렬화되지만 서로 다른 `couponId`의 행은 독립적으로 처리할 수 있다.
일반 쿠폰 조회에는 전용 락 조회 메서드를 사용하지 않으므로 모든 조회를 불필요하게 잠그지 않는다.

## Repository

`CouponRepository`에는 V2 발급 전용 조회 메서드 `findByIdForUpdate()`를 둔다.

- `LockModeType.PESSIMISTIC_WRITE`를 적용한다.
- JPQL에는 데이터베이스 전용 `FOR UPDATE` 문법을 직접 작성하지 않는다.
- Hibernate가 MariaDB 방언에 맞는 잠금 SQL을 생성한다.
- 쿠폰이 없을 수 있으므로 `Optional<Coupon>`을 반환한다.
- 기존 `findById()`는 일반 조회 용도로 유지한다.

실제 실행 SQL에서 다음 구문을 확인했다.

```sql
select
    ...
from
    coupon
where
    id = ?
for update
```

## Service와 트랜잭션

V2 구현은 `coupon.service.v2.V2CouponIssueService`에 둔다.
public `issueCoupon()` 전체를 하나의 `@Transactional` 경계로 묶는다.

```text
@Transactional 시작
-> findByIdForUpdate() 호출 및 잠금 획득
-> validateNotAlreadyIssued()
-> 발급 시각 생성
-> coupon.issue()
-> CouponIssue.create()
-> couponIssueRepository.save()
-> 커밋 또는 롤백
-> 잠금 해제
```

`getCouponByIdForUpdate()` 같은 private 보조 메서드에는 별도의 `@Transactional`을 적용하지 않는다.
이미 public 유스케이스의 트랜잭션 안에서 실행되기 때문이다.

영속 상태의 `Coupon`은 변경 감지로 갱신하므로 `couponRepository.save(coupon)`을 호출하지 않는다.
잠금도 트랜잭션 종료 시 데이터베이스가 해제하므로 수동 해제 코드를 작성하지 않는다.

## 중복 발급 검증

중복 발급 검증은 V2에서도 유지한다.

```text
요청 A: 쿠폰 잠금 -> 중복 없음 -> 발급 -> 커밋
요청 B: 잠금 대기 -> 잠금 획득 -> 중복 있음 -> 409
```

서비스 사전 검사는 안전한 비즈니스 오류 응답을 제공한다.
데이터베이스의 `(coupon_id, user_id)` UNIQUE 제약도 최종 방어선으로 계속 유지한다.

## 구현 버전 선택

V1과 V2가 모두 `CouponIssueUseCase`를 구현하므로 두 Service가 동시에 Bean으로 등록되면
Controller에 주입할 구현체를 결정할 수 없다.

다음 설정으로 하나의 구현체만 활성화한다.

```properties
coupon.issue.version=v2
```

- V1은 설정값이 `v1`일 때 활성화한다.
- V2는 설정값이 `v2`일 때 활성화한다.
- 내부 구현 버전만 바뀌며 외부 API URL과 응답 계약은 변경하지 않는다.

## Flyway

V2에서는 테이블, 컬럼, 인덱스 또는 제약 조건이 바뀌지 않는다.
비관적 락은 JPA 조회 방식의 변경이므로 V2 전용 Flyway 마이그레이션을 추가하지 않는다.

## 관찰 대상

V2는 정합성만 확인하고 끝내지 않는다.

- Hibernate SQL에 `FOR UPDATE`가 포함되는가
- 발급 수가 재고 수량과 정확히 일치하는가
- HTTP 500과 unexpected 응답이 제거되는가
- 락 대기로 평균, p95, p99가 얼마나 증가하는가
- DB 커넥션 풀 active 및 pending이 어떻게 변하는가
- Lock Wait 또는 timeout이 발생하는가
- HTTP Connector와 운영체제 연결 대기열이 먼저 포화되는가

애플리케이션 성능 측정에서는 SQL 콘솔 출력의 영향을 제거하기 위해
`spring.jpa.show-sql=false`를 사용한다. SQL 확인은 별도의 기능 검증 실행에서 수행한다.

## V2의 한계와 V3 가설

비관적 락은 같은 쿠폰의 발급 흐름 전체를 직렬화한다.
중복 조회와 발급 이력 저장이 끝날 때까지 쿠폰 행 잠금과 DB 커넥션을 점유한다.

V3에서는 조건부 Atomic UPDATE로 다음 가설을 검증한다.

```text
remaining_quantity > 0 조건을 포함한 UPDATE 한 번으로 재고를 감소시키면
명시적인 SELECT FOR UPDATE와 긴 락 보유 구간을 줄일 수 있다.
그 결과 V2보다 p95와 처리량이 개선될 수 있다.
```

V3도 동일한 쿠폰 행을 갱신하므로 경합 자체가 사라지는 것은 아니다.
동일한 부하 조건에서 V2와 V3를 수치로 비교한 뒤 최종 판단한다.
