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

- 동기 발급(V1~V3): 발급 이력 저장 후 `201 Created`
- Kafka 비동기 발급: DB 저장 전에 응답한다면 `202 Accepted`와 별도 계약

요청·응답 JSON과 오류 코드의 정본은 [API 계약](docs/api.md)입니다. 구현 버전이 바뀌어도 이 계약은 유지합니다.

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
- Flyway
- Redis, Kafka, Batch, Cache — 이후 단계에서 사용
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

연결 정보는 환경변수 또는 저장소 루트의 `.env` 파일로 주입합니다. `.env`는 커밋하지 않습니다.

```text
DB_USERNAME=root
DB_PASSWORD=MariaDB 비밀번호
```

PowerShell에서 환경변수로 직접 지정할 수도 있습니다:

```powershell
$env:DB_USERNAME="root"
$env:DB_PASSWORD="MariaDB 비밀번호"
./gradlew.bat bootRun
```

발급 구현 버전은 `application.properties`의 `coupon.issue.version`(`v1`, `v2`, `v3`)으로 선택합니다.

Flyway는 스키마 검증과 마이그레이션에 사용하며 Batch 자동 실행은 비활성화되어 있습니다.
Redis와 Kafka는 아직 실제 발급 흐름에 사용하지 않습니다.

## 현재 진행 상태

V1의 동시성 문제를 재현한 뒤 V2 비관적 락 구현과 100 VU 검증을 완료했습니다.
V2 설계와 실험 결과는 다음 문서에 기록했습니다.

- [V2 개발 가이드](docs/v2/development-guide.md)
- [V2 부하 테스트 결과](docs/v2/load-test-result.md)

완료:

- `Coupon`, `CouponIssue` JPA 엔티티와 `(coupon_id, user_id)` UNIQUE 제약
- 쿠폰 생성, 단건 조회, 발급, 사용자별 보유 쿠폰 조회 API
- DTO Validation과 안전한 공통 오류 응답
- V1 동기 발급 Service와 트랜잭션 경계
- 발급 기간, 재고, 중복 발급에 대한 도메인 및 DB 방어
- LAZY 연관관계를 함께 조회하는 사용자별 보유 쿠폰 JPQL
- 공통 API 계약, 공통 개발 가이드, V1 개발 가이드
- `PESSIMISTIC_WRITE`를 적용한 V2 쿠폰 행 잠금 조회
- 설정값으로 V1과 V2 발급 구현체 선택
- 실행 SQL의 `FOR UPDATE` 확인
- 재고 100개와 100 VU 조건에서 100건 발급, unexpected 0건 확인

완료 단계의 코드 상태는 Git 태그 `v1-baseline`, `v2-pessimistic-lock`으로 보존했습니다.

진행 중 (V3, 구현과 자동 테스트 완료, 부하 실험 대기):

- 조건부 Atomic UPDATE 발급을 구현하고 `Coupon.issue()`와 `validateIssuable()`의 책임을 분리했습니다.
- 구현 중 REPEATABLE READ 스냅샷 격리와 조건부 UPDATE의 충돌을 재현했고, V3 발급 트랜잭션을 READ COMMITTED로 낮춰 해결했습니다. 근거는 [V3 개발 가이드](docs/v3/development-guide.md)에 있습니다.
- V1·V2·V3가 같은 시나리오(재고 100, 사용자 300명 동시 요청)를 도는 동시성 자동 테스트를 추가했습니다. V2와 V3는 정확히 100장 발급, 예상 밖 예외 0건을 통과합니다.
- `CouponTest`의 보류 테스트 9개를 모두 채웠습니다.

보류:

- V3 k6 부하 실험과 V2 대비 수치 비교
- V2 유효 실행의 DB 사후 집계값 보존
- 1,000 VU 순간 연결에서 발생한 TCP 연결 거절과 HTTP 진입 용량의 별도 분석

아직 구현하지 않음:

- V4 Redis 이후의 모든 단계

## 다음 작업

1. k6에서 HTTP 409의 오류 코드, HTTP 500, 네트워크 오류를 분리 집계합니다.
2. V2와 V3를 동일한 데이터와 부하 조건에서 반복 측정하고 `docs/v3/load-test-result.md`에 기록합니다.
3. 정합성, p95, 처리량, Lock Wait와 Connection 점유를 비교해 V3 개발 가이드의 "결과"와 "판단"을 채웁니다.
4. `scripts/check-stage.sh v3`를 통과시킨 뒤 `v3-atomic-update` 태그를 남깁니다.

## 검사 장치

코드와 문서가 어긋난 채 남지 않도록 세 순간에 자동 검사가 돕니다. 상세 규칙은 [AGENTS.md](AGENTS.md)의 "작업 종료 조건"에 있습니다.

| 순간 | 실행 주체 | 스크립트 |
|---|---|---|
| Claude Code 턴 종료 | `.claude/settings.json` Stop 훅 | `scripts/check-turn.sh` |
| `git commit` | `.githooks/commit-msg` | `scripts/check-commit.sh` |
| GitHub push, PR | `.github/workflows/checks.yml` | 위 스크립트 + 테스트 + 링크 검사 |
| `vN-*` 태그 push | 같은 워크플로 | `scripts/check-stage.sh vN` |

직접 커밋하는 환경에서는 한 번만 아래를 실행해 커밋 검사를 설치합니다. Claude Code 세션은 자동으로 설치합니다.

```bash
git config core.hooksPath .githooks
```

## 개발 원칙

- 기술은 문제를 재현한 다음 도입합니다.
- 엔티티의 전체 Setter와 `@Data`는 사용하지 않습니다.
- 쿠폰은 `Coupon.create()`를 통해서만 생성합니다.
- 도메인 오류는 문자열이 아니라 `CouponErrorCode`로 식별합니다.
- 테스트는 메시지보다 예외 타입과 오류 코드를 우선 검증합니다.
- 비밀정보는 커밋하지 않고 환경변수로 주입합니다.
- 동시성 최적화 전후의 결과를 반드시 수치로 비교합니다!
