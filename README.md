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

V4부터는 Redis도 필요합니다. Docker Desktop으로 띄웁니다.

```bash
docker run -d --name coupon-redis -p 6379:6379 redis:7 redis-server --maxmemory-policy noeviction
```

`noeviction`을 지정하는 이유는 V4의 재고가 Redis 키 하나에 들어 있기 때문입니다. 메모리가 부족할 때 Redis가 그 키를 임의로 버리면, 이미 승인돼 저장 중인 발급이 있는 채로 재고가 다시 계산돼 수치가 어긋납니다. 기본값이기도 하지만 전제를 명시해 둡니다.

기본 접속 정보는 `localhost:6379`이며 `spring.data.redis.host`, `spring.data.redis.port`로 바꿉니다.

발급 구현 버전은 `application.properties`의 `coupon.issue.version`(`v1`, `v2`, `v3`, `v4`)으로 선택합니다.
저장소의 기본값은 현재 진행 단계에 맞춰 `v4`입니다. V3 비교 실험처럼 다른 버전을 실행할 때는 값을 바꾸거나 실행 시 덮어씁니다.

```powershell
$env:COUPON_ISSUE_VERSION="v3"
./gradlew.bat bootRun
```

`v1`, `v2`, `v3`은 Redis 없이 돌아갑니다. `v4`는 Redis가 켜져 있어야 합니다.

Flyway는 스키마 검증과 마이그레이션에 사용하며 Batch 자동 실행은 비활성화되어 있습니다.
Kafka는 아직 실제 발급 흐름에 사용하지 않습니다.

### 부하 테스트 실행

애플리케이션을 켠 뒤 쿠폰을 하나 만들고, 그 ID로 k6를 실행합니다. 매 실행마다 새 쿠폰을 사용합니다.

```bash
k6 run -e COUPON_ID=1 -e VUS=100 -e MAX_DURATION=30s load-tests/k6/coupon-issue-concurrency.js
```

- `VUS`: 동시 사용자 수. 각 VU가 고유한 `userId`로 정확히 한 번 요청합니다. 기본값 1000.
- `MAX_DURATION`: 이 시간 안에 끝나지 않으면 중단합니다. 기본값 30초.
- `BASE_URL`: 기본값 `http://localhost:8080`.
- CLI `--vus` 옵션은 사용자 ID 분배를 깨뜨리므로 쓰지 않습니다.

결과는 `coupon_issue_created`, `coupon_issue_sold_out`, `coupon_issue_duplicate`, `coupon_issue_conflict_other`, `coupon_issue_server_error`, `coupon_issue_network_error`, `coupon_issue_unexpected`로 나뉘어 출력됩니다.

지속 부하 실험은 별도 스크립트를 씁니다. 정해진 도착률로 일정 시간 계속 요청하므로 재고는 매진되지 않을 만큼 크게 만듭니다.

```bash
k6 run -e COUPON_ID=1 -e RATE=200 -e DURATION=30s --log-output=stdout load-tests/k6/coupon-issue-sustained.js
```

- `RATE`: 초당 요청 수. 기본값 100.
- `DURATION`: 지속 시간. 기본값 30초.
- `USER_ID_OFFSET`: 같은 쿠폰으로 다시 돌릴 때 이전 실행의 사용자 번호와 겹치지 않게 더할 값.
- `PRE_ALLOCATED_VUS`, `MAX_VUS`: 미리 준비할 VU 수와 상한. 기본값은 도착률에 맞춰 정해지며 1,000 미만으로 둡니다.

실험 전후와 도중의 상태는 `load-tests/snapshot.ps1`로 기록합니다. 애플리케이션의 커넥션 풀 지표(Actuator)와 DB의 `Innodb_row_lock%` 통계를 한 번에 찍습니다.

```powershell
./load-tests/snapshot.ps1 -Label before
./load-tests/snapshot.ps1 -Label during -WatchSeconds 30
./load-tests/snapshot.ps1 -Label after -CouponId 1
```

DB 비밀번호는 환경변수 `DB_PASSWORD`가 있으면 그것을 쓰고 없으면 물어봅니다.

커넥션 풀 크기를 바꿔가며 병목을 가를 때는 애플리케이션을 띄울 때 `DB_POOL_SIZE`를 줍니다. 기본값은 HikariCP 기본과 같은 10입니다. `application.properties`에 값을 직접 박으면 테스트에도 적용돼, 테스트 컨텍스트마다 풀이 생기면서 MariaDB `max_connections`를 넘겨 컨텍스트 로딩이 실패합니다.

```powershell
$env:DB_POOL_SIZE="50"
./gradlew.bat bootRun
```

실험은 다른 부하와 겹치지 않을 때 시작합니다. 시작 전 `snapshot.ps1`의 `active=0 pending=0`으로 앱이 한가한지 확인하고, 직전 실행이 끝나기 전에 다음 실행을 걸지 않습니다. 앞 실행이 아직 돌고 있으면 같은 `USER_ID_OFFSET` 구간을 다시 쓰게 되어 요청 대부분이 중복 발급으로 튕깁니다. 턴 종료 자동 검사(`gradlew test`)와도 겹치면 안 되며, 겹쳤는지는 `build/test-results/test/*.xml`의 `timestamp`로 확인합니다.

`-CouponId`를 준 사후 검증은 잔여 수량을 어디서 읽을지 스스로 정합니다. Redis에 그 쿠폰의 재고 키가 있으면 그것을(`remaining=...(redis)`), 없으면 DB의 `remaining_quantity`를(`remaining=...(db)`) 씁니다. V4는 발급 경로에서 DB 잔여 수량을 갱신하지 않으므로 DB 값으로 계산하면 실제로는 멀쩡한데 어긋난 것처럼 보이기 때문입니다. V1~V3로 발급한 쿠폰은 Redis에 키가 없어 예전과 똑같이 동작합니다. V4 쿠폰에는 발급 사용자 집합의 크기(`issued_users`)와 발급 이력 수의 차이(`issued_gap`)도 함께 찍습니다. Redis 접근은 `redis-cli`가 PATH에 있으면 그것을, 없으면 `docker exec coupon-redis redis-cli`를 씁니다.

## 현재 진행 상태

V1 동시성 문제 재현, V2 비관적 락, V3 조건부 Atomic UPDATE, V4 Redis Lua 원자 발급까지 구현과 부하 실험을 완료했습니다.
V4 설계와 실험 결과는 다음 문서에 기록했습니다.

- [V4 개발 가이드](docs/v4/development-guide.md)
- [V4 부하 테스트 결과](docs/v4/load-test-result.md)

이전 단계 문서: [V1 개발 가이드](docs/v1/development-guide.md), [V1 부하 테스트 결과](docs/v1/load-test-result.md), [V2 개발 가이드](docs/v2/development-guide.md), [V2 부하 테스트 결과](docs/v2/load-test-result.md), [V3 개발 가이드](docs/v3/development-guide.md), [V3 부하 테스트 결과](docs/v3/load-test-result.md)

완료:

- `Coupon`, `CouponIssue` JPA 엔티티와 `(coupon_id, user_id)` UNIQUE 제약
- 쿠폰 생성, 단건 조회, 발급, 사용자별 보유 쿠폰 조회 API
- DTO Validation과 안전한 공통 오류 응답
- V1 동기 발급 Service와 트랜잭션 경계, 100 VU에서 스냅샷 충돌로 82% HTTP 500 재현
- 발급 기간, 재고, 중복 발급에 대한 도메인 및 DB 방어
- LAZY 연관관계를 함께 조회하는 사용자별 보유 쿠폰 JPQL
- 공통 API 계약, 공통 개발 가이드, 단계별 개발 가이드
- V2 `PESSIMISTIC_WRITE` 행 잠금 발급과 `FOR UPDATE` SQL 확인, 100 VU에서 HTTP 500 제거
- V3 조건부 Atomic UPDATE 발급, `Coupon.issue()`와 `validateIssuable()` 책임 분리, V3 발급 트랜잭션 READ COMMITTED
- 설정값으로 V1·V2·V3 발급 구현체 선택
- V1·V2·V3가 같은 시나리오(재고 100, 사용자 300명 동시 요청)를 도는 동시성 자동 테스트
- k6 스크립트의 응답 원인별 분리 집계(201, 409 매진, 409 중복, 5xx, 네트워크 오류)와 환경변수 VU 수
- V2·V3 동일 조건 비교: 100 VU에서 V3 p95 273~310ms, V2 485ms, 6회 실행 모두 `consistency_gap` 0
- 1,000 VU에서 V2 실험의 원인 미상 "unexpected"가 TCP 연결 거절임을 분리 집계로 확인
- V4 지속 도착률 실험으로 V3의 `coupon` 행 경합을 재현. 단일 쿠폰 동기 발급이 초당 약 1,200건에서 포화하고 p95가 7ms에서 790ms로 뜀
- V4 Redis Lua 원자 발급: 중복 확인·재고 확인·재고 감소·사용자 등록을 스크립트 하나로 묶어 발급 경로에서 `coupon` 행 갱신 제거
- V4 지연 초기화: `issued` 복원을 먼저 하고 `stock` 키를 마지막에 만들어, 키의 존재 자체가 초기화 완료 표시가 되게 함
- V4 저장 실패 보상을 원인별로 분리: UNIQUE 위반은 재고만 되돌리고 `issued`의 실제 보유자는 지우지 않음
- V4 동시성·순차 테스트와, 구현을 일부러 망가뜨려 각 테스트가 지키는 범위를 확인한 변이 실험
- 동시성 테스트 본체를 버전별로 열어 V4만 Redis 재고를 읽게 함. V1~V3 테스트는 그대로
- 사후 검증 스크립트가 Redis 재고 키의 유무로 잔여 수량을 읽을 곳을 스스로 정함
- V4 실험 결과: 같은 날 V3 대비 행 잠금 대기 0회(V3는 800 req/s에서 요청의 99.97%), 400 req/s에서 p95 36.76ms → 13ms. 처리율은 늘지 않음

V4 완료. 완료 단계의 코드 상태는 Git 태그 `v1-baseline`, `v2-pessimistic-lock`, `v3-atomic-update`로 보존했고, V4는 `v4-redis-atomic` 태그를 만들 차례입니다.

보류:

- 1,000 VU 순간 연결에서 발생하는 TCP 연결 거절의 원인 분석과 HTTP 진입 용량 조정. Rate Limit과 Virtual Waiting Room 단계에서 다룹니다.
- V4가 800 req/s에서 막히는 새 병목의 정체. 커넥션 풀도(풀을 10에서 50으로 늘려도 처리율이 오르지 않음) Redis 왕복도(중앙값 0.47ms) 아님을 확인했지만 무엇인지는 밝히지 못했습니다. GC 로그, CPU 사용률, 커밋 fsync를 함께 기록하면 좁힐 수 있습니다.
- V4 지연 초기화의 순서(`issued` 먼저, `stock` 마지막)는 순차 테스트로 덮이지 않습니다. 동시 요청이 끼어드는 창을 결정적으로 재현하려면 코드에 지연을 주입해야 합니다.
- Redis 키의 수명. TTL을 두지 않았으며 만료 정책은 V9 Cache에서 정합니다.
- 한 대에서 애플리케이션, MariaDB, Redis, k6를 모두 돌리는 실험 환경의 한계. V4를 재려면 Docker가 켜져 있어야 하는데 Docker를 켜는 것 자체가 환경을 바꿉니다. 비교는 같은 날 같은 세션 안에서만 성립합니다.

아직 구현하지 않음:

- 로드맵의 "실험: Redis 성공 후 DB 실패"와 V5 Kafka 이후의 모든 단계

## 다음 작업

1. `scripts/check-stage.sh v4`를 통과시키고 `v4-redis-atomic` 태그를 만듭니다.
2. 로드맵의 "실험: Redis 성공 후 DB 실패"로 두 저장소가 어긋나는 상황을 재현합니다. V4가 "최선의 노력" 보상으로 남겨 둔 구멍입니다.
3. V5 Kafka 비동기 발급으로 넘어갑니다.

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
