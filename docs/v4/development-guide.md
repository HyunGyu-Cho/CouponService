# V4 Redis Atomic Operation Development Guide

상태: 초안 (문제 재현 완료, 가설 확정, 발급 경로 구현 완료, 부하 실험 전이라 미검증). 재현 수치는 [V4 부하 테스트 결과](load-test-result.md) 1부에 있다.
공통 규칙은 [공통 개발 가이드](../development-guide.md)를 따른다.

## 문제

### V3가 해결하지 못한 것

V3는 잠금 구간을 "UPDATE부터 커밋까지"로 줄였지만, 같은 쿠폰의 모든 발급 요청이 여전히 `coupon` 행 하나를 갱신한다.
InnoDB는 한 행을 동시에 갱신하지 못하므로, 동일 쿠폰의 발급은 V3에서도 DB 행 잠금 앞에서 한 줄로 선다.

```text
요청 N개 (같은 couponId)
-> 각각 트랜잭션 시작, 존재 확인, 중복 확인 (여기까지는 병렬)
-> UPDATE coupon ... WHERE id = ? AND remaining_quantity > 0   <- 행 잠금, 한 번에 하나
-> INSERT coupon_issue                                        <- 잠금 유지한 채 실행
-> 커밋                                                       <- 잠금 해제
```

한 트랜잭션이 잠금을 쥐고 있는 동안 나머지는 DB 안에서 대기하며, 대기하는 동안 각 요청은 **DB 커넥션 하나를 점유**한다.
커넥션 풀(HikariCP 기본 10개)이 모두 대기 중이면 그다음 요청은 커넥션을 얻기 위해 애플리케이션 안에서 또 대기한다.

따라서 예상되는 문제의 형태는 다음과 같다.

- 동시 요청이 늘수록 p95가 요청 수에 비례해 늘어난다 (직렬화의 결과).
- `Innodb_row_lock_waits`와 `Innodb_row_lock_time`이 요청 수에 비례해 증가한다.
- HikariCP `pending` 커넥션 수가 0보다 큰 상태가 지속된다.
- 대기가 30초(HikariCP `connection-timeout` 기본값)를 넘으면 커넥션 획득 실패로 HTTP 500이 난다.
- 대기가 50초(`innodb_lock_wait_timeout` 기본값)를 넘으면 Lock wait timeout으로 HTTP 500이 난다.

[V3 부하 테스트 결과](../v3/load-test-result.md)의 100 VU 실험은 오류 0건, p95 273~310ms로 끝나 이 문제가 드러나지 않았다.
1,000 VU 실험은 요청의 60%가 TCP 연결 단계에서 거절돼 DB까지 도달하지 못했다. 즉 **V3의 DB 경합 문제는 아직 재현되지 않았다.**

### 왜 지금까지 재현되지 않았나

1. 100 VU는 각 VU가 한 번만 요청하므로 부하가 0.3초 만에 끝난다. 잠금 대기가 쌓일 시간이 없다.
2. 1,000 VU 순간 연결은 애플리케이션이 아니라 로컬 HTTP 진입 구간(Tomcat 연결 수용, OS backlog)이 먼저 막는다.
3. 재고 100개는 100번의 UPDATE 후 매진되고, 매진 이후의 UPDATE는 갱신할 행이 없어 잠금 경합이 거의 없다.

### 재현 실험 설계

문제를 드러내려면 "순간 폭발"이 아니라 "지속되는 도착률"로 DB 행 잠금 앞에 줄이 서게 해야 한다.

조건:

| 항목 | 값 | 이유 |
|---|---|---|
| 대상 구현 | V3 (`coupon.issue.version=v3`) | 현재 기준 구현 |
| 쿠폰 재고 | 1,000,000 | 실험 동안 매진되지 않아 모든 요청이 실제로 UPDATE를 수행 |
| 부하 형태 | k6 `constant-arrival-rate`, 30초 지속 | 순간 연결 폭발을 피하고 DB에 지속 부하 |
| 도착률 단계 | 100, 200, 400, 800 req/s | p95가 어느 도착률부터 급격히 꺾이는지 관찰 |
| 사용자 ID | 실행마다 고유 (VU 번호와 iteration 조합) | 중복 발급 경로가 아닌 정상 발급 경로만 측정 |
| `preAllocatedVUs` / `maxVUs` | 도착률에 맞춰 조정, 1,000 VU 미만 | HTTP 진입 한계를 피함 |
| `spring.jpa.show-sql` | false | 기존 실험과 동일 |

관찰 지표:

```text
k6:      p50, p95, p99, http_req_failed, coupon_issue_* 분리 집계, dropped_iterations
앱:      Actuator hikaricp.connections.active / pending / timeout (실행 전후 차이)
DB:      SHOW GLOBAL STATUS LIKE 'Innodb_row_lock%' (실행 전후 차이)
         Innodb_row_lock_waits, Innodb_row_lock_time, Innodb_row_lock_time_avg, Innodb_row_lock_time_max
DB 사후: issue_count, remaining_quantity, consistency_gap (V3 문서와 같은 SQL)
```

재현 성공의 기준. 아래 중 하나 이상이 도착률 증가에 따라 나타나면 "V3의 DB 경합 문제가 재현됐다"고 본다.

- 도착률을 두 배로 올렸을 때 p95가 두 배 이상 늘어난다 (선형 이상의 악화).
- HikariCP `pending`이 실행 중 지속적으로 0보다 크다.
- `Innodb_row_lock_time_avg`가 도착률에 비례해 증가한다.
- 커넥션 획득 실패 또는 Lock wait timeout으로 HTTP 500이 발생한다.

같은 실험을 V2로도 돌려, 이 문제가 V3 특유의 것이 아니라 "DB 행을 갱신하는 동기 발급 모두"의 문제임을 확인한다.

### 실험 준비 (완료)

관측 장치는 발급 흐름을 바꾸지 않으므로 V4 도입 전에 추가했다. 실행 방법은 README "부하 테스트 실행"에 있다.

- `application.properties`에서 Actuator `metrics` 엔드포인트를 노출했다. `hikaricp.connections.active`, `pending`, `timeout`을 읽는다.
- `load-tests/k6/coupon-issue-sustained.js`: `constant-arrival-rate` 시나리오. 도착률, 지속 시간, VU 상한을 환경변수로 받는다. 사용자 ID는 `exec.scenario.iterationInTest`로 시나리오 전체에서 고유하다.
- `load-tests/k6/lib/issue-response.js`: 순간 부하와 지속 부하 스크립트가 공유하는 응답 분류와 집계.
- `load-tests/snapshot.ps1`: 실험 전후에 커넥션 풀 지표와 `Innodb_row_lock%` 통계를, 실험 도중에는 1초 간격으로 `active`/`pending`을 기록한다.

### 실험 환경의 한계

애플리케이션, MariaDB, k6가 한 대의 Windows에서 돈다. CPU를 서로 빼앗으므로 높은 도착률에서의 절대 수치는 신뢰하지 않고, 도착률 대비 지표의 **기울기**와 오류 발생 여부로 판단한다.

### 재현 결과 (2026-09-15)

재현됐다. 상세 수치는 [V4 부하 테스트 결과](load-test-result.md) 1부에 있다.

| 도착률 (req/s) | p95 | 잠금 대기 비율 / 대기당 | HikariCP `pending` |
|---:|---:|---|---|
| 200~800 | 4~7ms | 0~23% / 1.2~1.3ms | 0 (정지 순간 제외) |
| 1,600 | 790ms | 100% / 4.7ms | 30초 내내 188~189 |

- 이 환경에서 단일 쿠폰의 동기 발급은 초당 약 1,200건에서 포화된다. 포화되면 커넥션 10개와 Tomcat 스레드 200개가 전부 소진되고 p95가 100배로 뛴다.
- 포화 상태에서 커넥션 시간의 절반 이상이 행 잠금 대기였다. 병목은 `coupon` 행이다.
- 정상 범위(400, 800)에서도 실행마다 한 번, 원인 미상의 1~2초 정지가 왔고 그때마다 Tomcat 스레드 전부가 행 잠금 뒤에 쌓였다.
- 재현 기준 네 가지 중 셋(p95 배증, `pending` 지속, 잠금 대기 시간 증가)을 충족했다. HTTP 500은 나지 않았다.

## 가설

```text
재고 감소와 중복 확인을 Redis 원자 연산(Lua 스크립트 하나)으로 옮기면
coupon 행 갱신이 발급 경로에서 사라져 DB 행 잠금 앞의 줄 서기가 없어진다.
그 결과 같은 환경에서 포화 지점이 초당 1,200건보다 높아지고,
포화 전 구간에서는 한 번의 정지가 전체 스레드를 소진시키는 호송 현상이 약해진다.
```

근거는 재현 실험의 두 관찰이다. 포화 상태에서 커넥션 시간의 절반 이상이 행 잠금 대기였으므로, 행 갱신을 빼면 그만큼의 대기가 사라진다. 정지 시 전원이 쌓인 곳도 같은 행이었다.

검증 방법은 1부와 같은 조건(쿠폰 하나, 재고 1,000,000, 도착률 400, 800, 1,600)에서 V4를 돌려 p95, 실제 처리율, `pending`, `Innodb_row_lock%`를 나란히 비교하는 것이다. V4에서는 `coupon` 행을 갱신하지 않으므로 `Innodb_row_lock_waits` 증가분이 0에 가까워야 한다.

Redis가 재고를 결정하고 DB가 이력을 저장하면 두 저장소가 어긋날 수 있다. 이 문제는 로드맵의 "실험: Redis 성공 후 DB 실패" 단계에서 별도로 재현한다.

## 변경

설계 초안이다. 구현하면서 달라진 결정은 이 절을 고쳐 기록한다.

### 설계 원칙

- 발급 경로에서 `coupon` 행을 갱신하지 않는다. 재현 실험에서 병목으로 확인된 그 행이다.
- 재고 감소와 중복 확인은 Redis 안에서 Lua 스크립트 하나로 원자적으로 끝낸다. 두 명령 사이에 다른 요청이 끼어들 틈을 없앤다.
- Redis가 승인한 발급만 DB `coupon_issue`에 저장한다. DB UNIQUE 제약은 최종 방어선으로 그대로 둔다.
- 외부 API 계약은 유지한다. 동기 `201 Created`, 요청·응답 JSON 동일.
- 사용하지 않는 추상화를 미리 만들지 않는다. 처음에는 V4 Service 하나에 담고, 커지면 분리한다.

### 발급 흐름

```text
1. 쿠폰 존재와 발급 기간 확인: DB 일반 조회 (잠금 없음, 행 갱신 없음)
2. Lua 스크립트 실행 (원자적)
     이미 발급한 사용자인가?  -> 중복
     재고 키가 없는가?        -> 미초기화
     재고가 0 이하인가?       -> 매진
     재고 1 감소, 사용자 등록 -> 승인
3. 미초기화면 DB에서 초기화한 뒤 2를 한 번 재시도
4. 승인이면 CouponIssue 저장 (1번에서 읽은 Coupon 을 그대로 참조, 즉시 flush)
5. 저장 실패 시 실패 원인에 따라 Redis 보상 후 예외 전파
```

V3와 비교하면 "조건부 UPDATE"가 "Lua 스크립트"로 바뀌고, DB에는 INSERT만 남는다. `coupon_issue` INSERT는 행마다 다른 키를 쓰므로 같은 쿠폰의 요청끼리 잠금을 다투지 않는다.

### Redis 키

| 키 | 타입 | 값 | 용도 |
|---|---|---|---|
| `coupon:{couponId}:stock` | String (정수) | 잔여 수량 | Lua 에서 확인하고 1 감소 |
| `coupon:{couponId}:issued` | Set | 발급받은 userId 집합 | Lua 에서 중복 확인과 등록 |

- 키 이름은 `coupon:` 접두사와 쿠폰 ID로 시작해 한 쿠폰의 키가 모여 보이게 한다.
- TTL 은 두지 않는다. 만료 정책은 V9 Cache 단계의 주제다.
- 발급 기간은 Redis 에 두지 않는다. 1번 단계의 DB 조회가 이미 `Coupon` 을 읽으므로 거기서 검증한다.

### Lua 스크립트

`src/main/resources/redis/issue-coupon.lua` 에 두고 `DefaultRedisScript<Long>` 빈으로 로드한다. 반환값은 정수 하나다.

```text
KEYS[1] = stock 키, KEYS[2] = issued 키, ARGV[1] = userId

issued 에 userId 가 있으면            -> -1  (중복)
stock 키가 없으면                     -> -3  (미초기화)
stock <= 0 이면                       -> -2  (매진)
그 외: stock 을 1 감소, issued 에 userId 추가 -> 감소 후 잔여 수량 (0 이상)
```

- Redis 는 스크립트 하나를 실행하는 동안 다른 명령을 끼워 넣지 않는다. 이것이 "중복 확인과 재고 감소의 원자성"이다.
- 반환 코드는 Service 가 `CouponErrorCode` 로 바꾼다. `-1 -> DUPLICATE_ISSUE`, `-2 -> SOLD_OUT`. `-3` 은 오류가 아니라 초기화 신호다.
- 매직 넘버는 Service 의 상수로 이름을 붙인다.

### Redis 초기화

쿠폰 생성 시점이 아니라 **첫 발급 요청에서 지연 초기화**한다. 이유는 두 가지다.

- 쿠폰 생성 트랜잭션 안에서 Redis 를 쓰면, DB 가 롤백돼도 Redis 키는 남는다. 커밋 이후 훅으로 풀 수 있지만 장치가 하나 더 생긴다.
- V4 이전에 만든 쿠폰도 V4 로 발급할 수 있어야 한다.

초기화 절차는 다음과 같다. Lua 가 `-3` 을 반환한 요청이 수행한다.

```text
1. DB 에서 기존 발급자 userId 목록을 읽는다: select user_id from coupon_issue where coupon_id = ?
2. SADD coupon:{id}:issued 목록 전부       -- 목록이 비면 생략, 중복 추가는 무해
3. stock = total_quantity - 목록 크기
4. SET coupon:{id}:stock stock NX          -- 마지막. 이미 있으면 건드리지 않는다
5. Lua 를 한 번만 재시도한다. 또 -3 이면 예외
```

**`stock` 키를 마지막에 만드는 것이 이 절차의 핵심이다.** Lua 가 발급을 승인하려면 `stock` 키가 있어야 하므로, `stock` 키의 존재 자체가 "`issued` 복원이 끝났다"는 완료 표시가 된다. 별도의 초기화 락 없이 "초기화가 끝나기 전에는 아무도 발급받지 못한다" 가 성립한다.

순서를 뒤집어 `SET stock` 을 `SADD issued` 보다 먼저 하면 다음 창이 열린다.

```text
A: SET coupon:1:stock 950 NX      (성공, 이 순간부터 stock 키 존재)
                                   B: Lua 실행
                                      issued 가 아직 비어 있음 -> 중복 아님
                                      stock > 0 -> 승인, 재고 감소, SADD userB
A: SADD coupon:1:issued (기존 발급자 전부)
```

B 가 이미 `coupon_issue` 에 행이 있는 사용자면 중복 검사를 통과한다. DB UNIQUE 제약이 INSERT 를 막으므로 불변식 자체는 지켜지지만, 그 실패는 보상을 부르고 보상은 Redis 상태를 더 망가뜨린다 (아래 "DB 저장과 보상" 참고). 최종 방어선이 막아준다는 것이 설계가 안전하다는 뜻은 아니다.

절차의 멱등성과 동시 실행:

- 두 요청이 동시에 `-3` 을 받아도 `SADD` 는 여러 번 해도 결과가 같고 `SET NX` 는 한쪽만 성공한다. 재시도 이후에는 Lua 가 다시 원자성을 보장한다.
- 1번의 목록 조회와 4번의 `SET NX` 사이에 `coupon_issue` 행이 늘어나는 일은 없다. 행이 늘어나려면 Lua 승인이 필요하고 승인에는 `stock` 키가 필요한데, 그 키가 아직 없기 때문이다. 재고 계산의 기준이 흔들리지 않는다.
- 2번까지 하고 프로세스가 죽으면 `issued` 만 채워진 상태가 남는다. 다음 요청이 같은 절차를 다시 수행하므로 복구된다.
- 목록이 크면 `SADD` 를 나눠 보낸다. 부하 실험용 쿠폰은 매번 새로 만들어 목록이 비어 있지만, 초기화 비용이 기존 발급 수에 비례한다는 사실은 남는다.

이 안전성은 `stock` 키가 사라지지 않는다는 전제 위에 있다. 키가 축출되면 이미 승인돼 INSERT 중인 요청이 있는 채로 재계산이 돌아 재고가 어긋난다. TTL 을 두지 않는 것에 더해 Redis 를 `maxmemory-policy noeviction` 으로 띄운다.

### DB 저장과 보상

- 발급 전체를 하나의 public `@Transactional` 메서드로 묶는 공통 규칙을 따른다. 트랜잭션 안에서 Redis 를 호출하고 `CouponIssue` 를 저장한다. `coupon` 행을 잠그지 않으므로 트랜잭션이 커넥션을 쥐는 시간은 INSERT 한 번 분량이다.
- 격리 수준은 DB 기본값으로 되돌린다. V3 가 READ COMMITTED 로 낮춘 이유였던 "읽은 뒤 바뀐 행의 UPDATE" 가 V4 에는 없다. 단, 1번 단계의 `coupon` SELECT 뒤에 다른 트랜잭션이 그 행을 바꾸는 일도 V4 에서는 없으므로 스냅샷 충돌이 나지 않는다. 구현 중 충돌이 재현되면 이 결정을 고친다.
- **저장 실패는 트랜잭션 안에서 확정한다.** `save()` 만 하면 JPA 는 INSERT 를 커밋 시점까지 미루고, UNIQUE 위반은 메서드가 끝난 뒤에 터진다. 그러면 메서드 안의 보상 코드는 실행되지 않는다. `saveAndFlush()` 로 INSERT 를 메서드 안에서 실행해 실패를 그 자리에서 잡는다.
- **보상은 실패 원인에 따라 다르다.** 되돌려야 하는 것은 "Redis 가 승인했는데 DB 에 반영되지 않은 것" 뿐이다.

| 실패 | DB 상태 | 보상 | 응답 |
|---|---|---|---|
| DB 장애, 그 밖의 롤백 | 행 없음 | `INCR stock`, `SREM issued userId` | 예외 그대로 전파 |
| UNIQUE 위반 | 행이 이미 있음 | `INCR stock` 만 | `DUPLICATE_ISSUE` (409) |

`INCR stock` 이 양쪽에 다 있는 이유는 재고의 정의가 `stock = total_quantity - count(coupon_issue)` 이기 때문이다. UNIQUE 위반에서는 `count` 가 늘지 않았는데 재고만 줄었으므로 되돌린다.

UNIQUE 위반에서 `SREM` 까지 하면 안 된다. 그 사용자는 DB 상 실제 보유자인데 `issued` 에서 사라지므로, 재시도할 때마다 다시 승인받고 다시 INSERT 에 실패하는 루프에 빠진다. `SCARD issued = count(coupon_issue)` 검증도 깨진다.

UNIQUE 위반은 정상 흐름에서 나오지 않는다. 나온다면 Redis `issued` 가 DB 보다 뒤처졌다는 신호이므로 경고 로그를 남긴다.

이 보상은 "최선의 노력"이다. Redis 는 성공했는데 DB 저장과 보상이 모두 실패하면 두 저장소가 어긋난다. 이 상황은 로드맵의 "실험: Redis 성공 후 DB 실패" 에서 재현하고 V8 Reconciliation 에서 다룬다. V4 는 이 한계를 알고 시작한다.

### `coupon.remaining_quantity` 의 의미 변화

V4 부터 실시간 재고는 Redis `stock` 키에 있고, DB `coupon.remaining_quantity` 는 발급 경로에서 갱신하지 않는다. 따라서

- 정합성 불변식의 정본은 `발급 수 = count(coupon_issue)` 와 `Redis stock = total_quantity - 발급 수` 다.
- `GET /api/coupons/{couponId}` 의 `remainingQuantity` 는 DB 값이므로 V4 에서는 최신이 아닐 수 있다. 이 사실은 [API 계약](../api.md) 에 적는다. 실시간 값 반영은 V8 대사 또는 V9 캐시에서 정한다.
- 부하 실험의 사후 검증 SQL 도 V4 용으로 바꾼다: `total_quantity - (GET stock) = count(coupon_issue)`, `SCARD issued = count(coupon_issue)`.

### 엔티티 변경

`Coupon.validateIssuable()` 은 재고까지 검사하므로 V4 에서 쓰면 DB 의 낡은 재고로 잘못 거부한다. 발급 기간만 검사하는 public 메서드를 하나 둔다. 기존 `validateIssuablePeriod()` 를 public 으로 열되, 기존 호출부와 이름 규칙(`validate` 는 예외 또는 무반환)을 유지한다.

### 구성

- `coupon.service.v4.V4CouponIssueService`, `@ConditionalOnProperty(coupon.issue.version = v4)`.
- Lua 스크립트 빈: `DefaultRedisScript<Long>` 을 `ClassPathResource("redis/issue-coupon.lua")` 로 만드는 `@Configuration`. 스크립트 로드는 V4 전용이므로 `coupon.service.v4` 패키지 안에 둔다.
- 키 이름은 `V4RedisKeys` 한 곳에 둔다. 발급 서비스와 테스트가 같은 규칙을 보게 하려는 것이다.
- Redis 접근은 `StringRedisTemplate`. Spring Boot 자동 구성을 쓰고 `spring.data.redis.host`, `port` 만 설정한다.
- `management.health.redis.enabled` 는 V4 를 켤 때 `true` 로 되돌린다.
- 로컬 Redis 는 Docker Desktop 으로 띄운다. README "로컬 실행" 에 추가한다.

```bash
docker run -d --name coupon-redis -p 6379:6379 redis:7
```

- CI 워크플로의 `build-test` 와 `stage` job 에 `redis:7` 서비스를 추가한다.

### 테스트

- `V4CouponIssueConcurrencyTest`: 기존 `CouponIssueConcurrencyTestBase` 를 그대로 상속한다. 단, 베이스가 `remaining_quantity` 로 불변식을 검증하므로 V4 에서는 그 검증이 맞지 않는다. 베이스의 잔여 수량 조회를 버전별로 바꿀 수 있게 손보거나, V4 테스트가 Redis `stock` 을 읽도록 한다. 어느 쪽이든 "발급 수 == 전체 - 잔여" 의 잔여는 Redis 값이어야 한다.
- `V4CouponIssueServiceTest`: 순차 시나리오. 중복, 매진, 기간 전후, 없는 쿠폰, 그리고 **V4 전용으로 미초기화 쿠폰의 첫 발급**(지연 초기화)과 **기존 발급 이력이 있는 쿠폰의 초기화**(발급 수를 뺀 재고, issued 집합 복원).
- 보상 경로는 따로 만든다. `coupon_issue` 에 행을 넣고 `stock` 키만 손으로 만들어 "명단이 DB 보다 뒤처진" 상태를 재현하면 Redis 승인 뒤 UNIQUE 위반이 결정적으로 난다. 재고가 되돌아오는지, `issued` 에 그 사용자가 남는지, 재시도가 DB 까지 가지 않는지를 확인한다.

자동 테스트가 실제로 무엇을 지키는지는 구현을 일부러 망가뜨려 확인했다.

| 설계 결정 | 깨뜨렸을 때 실패하는 테스트 |
|---|---|
| 초기화가 `issued` 를 복원한다 | `initializesStockAndIssuedUsersFromIssueHistory` |
| UNIQUE 위반에서 `SREM` 하지 않는다 | `keepsIssuedUserAndRestoresStockOnUniqueViolation` |
| `issued` 복원을 `stock` 생성보다 먼저 한다 | **없음** |

초기화 순서를 뒤집어도 순차 테스트는 전부 통과한다. 단일 스레드에서는 두 명령이 재시도 전에 모두 끝나기 때문이다. 이 결정은 두 명령 사이에 다른 요청이 끼어드는 창에서만 드러나고, 그 창을 결정적으로 재현하려면 코드에 지연을 주입해야 한다. 지금은 넣지 않고 한계로 적어 둔다.
- 테스트는 실행 후 Redis 키를 지운다. DB 정리와 같은 위치(`@AfterEach`)에서 한다.

### 구현 순서

1. Lua 스크립트와 로드 설정
2. `Coupon` 기간 검증 메서드
3. `V4CouponIssueService` 정상 경로 (발급자 userId 목록 조회 메서드와 초기화 포함)
4. 오류 경로와 원인별 보상
5. 테스트 두 개
6. README 로컬 실행, CI Redis 서비스, `docs/api.md` 주석
7. 1부와 같은 조건으로 부하 실험, 2부 기록

## 결과

아직 없음.

## 판단

아직 없음.
