# V3 Atomic UPDATE Development Guide

상태: **초안, 미검증.** 코드는 있지만 테스트와 부하 실험을 거치지 않았다.
이 문서는 V3가 완료될 때까지 "현재 무엇이 문제인지"를 기록하는 작업 문서이며, 완료 시점에 V2 가이드와 같은 형식으로 다시 쓴다.

## V3 도입 배경

V2 비관적 락은 100 VU 조건에서 HTTP 500을 제거했지만, 쿠폰 행 잠금을 발급 트랜잭션 전체 동안 유지해 요청을 직렬화한다.
p95 343.19ms가 측정됐고 락 보유 구간 동안 DB 커넥션도 함께 점유된다.

## 가설

```text
remaining_quantity > 0 조건을 포함한 UPDATE 한 번으로 재고를 감소시키면
명시적인 SELECT FOR UPDATE와 긴 락 보유 구간을 줄일 수 있다.
그 결과 동일 부하에서 V2보다 p95와 처리량이 개선될 수 있다.
```

V3도 같은 쿠폰 행을 갱신하므로 경합 자체가 사라지지는 않는다. V2와 같은 조건에서 수치로 비교한 뒤 판단한다.

## 설계한 발급 흐름

```text
@Transactional 시작
-> 중복 발급 확인 (coupon_issue 조회)
-> 조건부 UPDATE: remaining_quantity > 0 AND 발급 기간 안이면 1 감소
-> 갱신 행 수가 0이면 재고를 바꾸지 않고 원인만 판별해 오류 응답
-> CouponIssue 저장
-> 커밋
```

Coupon 엔티티를 미리 조회하지 않으므로 `CouponIssue`의 연관관계는 `getReferenceById()`로 얻은 참조를 사용한다.

## 초안에서 발견한 문제 (2026-09-14 점검)

현재 커밋된 `V3CouponIssueService` 초안은 아래 문제로 위 설계와 다르다.

1. **`Coupon.validateIssuable()`이 재고를 감소시킨다.** 컨벤션의 "validate는 상태를 바꾸지 않는다" 규칙에 어긋나고, V3 실패 원인 판별 시 재고가 한 번 더 줄 수 있다. 같은 변경으로 V1·V2가 `issue()` 대신 `validateIssuable()`을 호출하게 되어 V1·V2 개발 가이드의 흐름과도 어긋났다.
2. **중복 확인보다 조건부 UPDATE가 먼저 실행된다.** 중복 사용자는 예외로 롤백돼 정합성은 유지되지만, 매 요청마다 불필요한 행 갱신과 롤백이 발생한다.
3. **`@Modifying` 쿼리 뒤 `findById()`가 영속성 컨텍스트의 옛 값을 볼 수 있다.** 실패 원인 판별이 틀릴 수 있다. `clearAutomatically = true` 또는 판별 전용 조회로 해결한다.
4. JPQL 파라미터 이름 오타 `isseudAt`.

## 완료 조건

[AGENTS.md](../../AGENTS.md)의 단계 완료 체크리스트를 따른다.

- 위 문제 4건 수정
- `CouponTest` 발급 관련 테스트와 V1·V2·V3 동시성 자동 테스트 통과
- V2와 동일 조건의 k6 측정 결과를 `load-test-result.md`에 기록
- 이 문서를 문제 → 가설 → 변경 → 결과 → 판단 형식으로 완성
- README "현재 진행 상태" 갱신 후 `v3-atomic-update` 태그
