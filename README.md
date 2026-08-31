# 선착순 쿠폰 발급 서비스

Spring Boot와 MariaDB로 가장 단순한 선착순 쿠폰 발급 서비스를 먼저 구현한 뒤, 실제 동시성 문제와 장애를 재현하면서 비관적 락, Atomic UPDATE, Redis, Kafka 등을 단계적으로 도입하는 학습 프로젝트입니다.

프로젝트의 목표는 많은 기술을 사용하는 것이 아니라 다음 질문에 근거를 갖고 답할 수 있는 상태가 되는 것입니다.

- 현재 구조에서 어떤 정합성 문제나 병목이 발생하는가?
- 새 기술이 어떤 문제를 해결하는가?
- 복잡도 증가를 감수할 가치가 있는가?
- 실제 서비스에서도 유지할 것인가?

> Redis, Kafka, Batch 등의 의존성은 미리 추가되어 있지만, 직전 단계의 문제를 직접 재현하기 전에는 사용하지 않습니다.

## 핵심 요구사항

- 쿠폰 생성
- 쿠폰 조회
- 쿠폰 발급
- 사용자별 보유 쿠폰 조회

### 비즈니스 규칙

- 쿠폰은 정해진 수량까지만 발급할 수 있습니다.
- 한 사용자는 동일한 쿠폰을 한 번만 받을 수 있습니다.
- 재고가 0이면 발급할 수 없습니다.
- 발급 기간 외에는 발급할 수 없습니다.

### 반드시 지켜야 하는 불변식

```text
발급 수 <= 전체 쿠폰 수량
(coupon_id, user_id) 기준 발급 건수 <= 1
```

## API 초안

```http
POST /api/coupons
GET  /api/coupons/{couponId}
POST /api/coupons/{couponId}/issue
GET  /api/users/{userId}/coupons
```

- 초기 동기 발급: `200 OK` 또는 `201 Created`
- Kafka 비동기 발급: DB 저장 전에 응답한다면 `202 Accepted`

## 데이터 모델

### `coupon`

```text
id
name
total_quantity
remaining_quantity
start_at
end_at
created_at
```

### `coupon_issue`

```text
id
coupon_id
user_id
issued_at
```

중복 발급의 최종 방어선으로 다음 UNIQUE 제약을 둡니다.

```sql
UNIQUE (coupon_id, user_id)
```

MariaDB를 최종 Source of Truth로 사용합니다.

## 단계별 로드맵

| 단계 | 구현 및 실험 | 핵심 학습 내용 |
|---|---|---|
| V1 | REST + JPA + MariaDB | 가장 단순한 동기 발급, 트랜잭션 |
| V2 | 비관적 락 | Row Lock, Lock Wait, Deadlock, Connection 점유 |
| V3 | Atomic UPDATE | 조건부 UPDATE와 비관적 락의 성능·복잡도 비교 |
| V4 | Redis 원자 연산과 Lua | DB 경합 감소, 중복 확인과 재고 감소의 원자성 |
| 실험 | Redis 성공 후 DB 실패 | Dual Write, 보상 처리, 데이터 불일치 |
| V5 | Kafka 비동기 발급 | 트래픽 버퍼링, Producer/Consumer 분리, Traffic Smoothing |
| V6 | 멱등성 | 중복 메시지 처리, `eventId`, UNIQUE 제약 |
| V7 | Retry, DLT, Replay | Consumer 장애 복구, Poison Message |
| V8 | Reconciliation | Redis 예약 결과와 DB 발급 결과 대사 |
| V9 | Cache | Cache Aside, TTL, Stale Data, Cache Stampede |
| V10 | SOLD_OUT Flag | 매진 이후 불필요한 Redis 연산 제거 |
| V11 | Rate Limit | 사용자·클라이언트별 과도한 요청 제한 |
| V12 | Virtual Waiting Room | 시스템 전체 유입량 제어와 가용성 보호 |

최종 구조의 방향은 다음과 같습니다.

```text
사용자
  -> Virtual Waiting Room
  -> Rate Limit
  -> SOLD_OUT 확인
  -> Redis Atomic/Lua
  -> Kafka
  -> Consumer
  -> MariaDB
```

## 단계별 검증 방법

각 단계에서 k6 부하 테스트와 장애 테스트를 수행합니다.

V1의 첫 동시성 실험은 쿠폰 100장을 만들고 1,000명이 동시에 발급을 요청하는 것입니다.

확인할 항목:

- 정확히 100장만 발급됐는가?
- 101장 이상 발급됐는가?
- `remaining_quantity`가 음수가 됐는가?
- 동일 사용자에게 중복 발급됐는가?
- TPS, 평균 응답시간, p95, p99는 어떻게 변했는가?
- DB Connection과 Lock Wait는 어떻게 변했는가?
- 오류율과 Redis/Kafka 처리량은 어떻게 변했는가?

각 버전의 실험 결과는 다음 형식으로 기록합니다.

```text
문제: 어떤 문제가 발생했는가?
가설: 원인이 무엇이라고 판단했는가?
변경: 어떤 기술이나 구조를 도입했는가?
결과: TPS, 응답시간, 오류율, 자원 사용량은 어떻게 변했는가?
판단: 복잡도 증가를 감수할 가치가 있었는가?
```

## 기술 스택

- Java 21
- Spring Boot 4.1.1
- Spring MVC
- Spring Data JPA
- MariaDB 12.3 / MariaDB Connector/J
- Validation, Actuator
- Redis, Kafka, Batch, Cache, Flyway — 이후 단계에서 사용
- JUnit 5, AssertJ
- k6

## 로컬 실행

기본 MariaDB 연결 정보:

```text
Host: localhost
Port: 3307
Database: coupon_service
```

데이터베이스 생성:

```sql
CREATE DATABASE coupon_service
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;
```

PowerShell 환경변수 설정 및 실행:

```powershell
$env:DB_USERNAME="root"
$env:DB_PASSWORD="MariaDB 비밀번호"
./gradlew.bat bootRun
```

현재 Flyway와 Batch 자동 실행은 비활성화되어 있습니다. Redis와 Kafka도 아직 실제 발급 흐름에 사용하지 않습니다.

## 현재 진행 상태

V1의 단순 동기 쿠폰 API 구현을 완료했습니다. 빌드·실행 확인과 동시성 실험은 별도로 진행합니다.

완료:

- `Coupon`, `CouponIssue` JPA 엔티티와 `(coupon_id, user_id)` UNIQUE 제약
- 쿠폰 생성, 단건 조회, 발급, 사용자별 보유 쿠폰 조회 API
- DTO Validation과 안전한 공통 오류 응답
- V1 동기 발급 Service와 트랜잭션 경계
- 발급 기간, 재고, 중복 발급에 대한 도메인 및 DB 방어
- LAZY 연관관계를 함께 조회하는 사용자별 보유 쿠폰 JPQL
- V1 API 계약과 개발 가이드

보류:

- 사용자가 직접 수행할 빌드와 API 실행 확인
- `CouponTest`의 `@Disabled` 테스트 구현
- k6 동시성 실험과 결과 기록

아직 구현하지 않음:

- V2 비관적 락과 이후의 모든 최적화 단계

## 다음 작업

1. 사용자가 V1 빌드와 주요 API 실행을 확인합니다.
2. 현재 상태에 `v1-baseline` Git 태그를 남깁니다.
3. 쿠폰 100장에 1,000명이 요청하는 k6 테스트로 동시성 문제를 재현합니다.
4. 실험 결과를 `문제 -> 가설 -> 변경 -> 결과 -> 판단` 형식으로 기록합니다.
5. 문제 재현 후 V2 비관적 락 구현을 시작합니다.

## 개발 원칙

- 기술은 문제를 재현한 다음 도입합니다.
- 엔티티의 전체 Setter와 `@Data`는 사용하지 않습니다.
- 쿠폰은 `Coupon.create()`를 통해서만 생성합니다.
- 도메인 오류는 문자열이 아니라 `CouponErrorCode`로 식별합니다.
- 테스트는 메시지보다 예외 타입과 오류 코드를 우선 검증합니다.
- 비밀정보는 커밋하지 않고 환경변수로 주입합니다.
- 동시성 최적화 전후의 결과를 반드시 수치로 비교합니다.
