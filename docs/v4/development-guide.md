# V4 Redis Atomic Operation Development Guide

상태: 초안 (문제 정의 중, 미검증). V3에서 남은 DB 경합 문제를 재현하기 전에는 Redis를 발급 흐름에 넣지 않는다.
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

### 실험 전에 필요한 준비

- Actuator `metrics` 엔드포인트 노출. 현재 `application.properties`에는 health만 기본 노출된다.
- k6 스크립트에 `constant-arrival-rate` 시나리오 추가 또는 별도 스크립트 작성. 사용자 ID는 `exec.vu.idInTest`와 `exec.vu.iterationInScenario`를 조합해 고유하게 만든다.
- DB 상태값 전후 차이를 기록하는 SQL 또는 스크립트.

이 준비는 관측 장치일 뿐 발급 흐름을 바꾸지 않으므로 V4 도입 전에 추가해도 된다.

### 실험 환경의 한계

애플리케이션, MariaDB, k6가 한 대의 Windows에서 돈다. CPU를 서로 빼앗으므로 높은 도착률에서의 절대 수치는 신뢰하지 않고, 도착률 대비 지표의 **기울기**와 오류 발생 여부로 판단한다.

## 가설

문제가 재현된 뒤에 적는다. 예상되는 방향만 미리 둔다.

```text
재고 감소와 중복 확인을 Redis 원자 연산(Lua 스크립트)으로 옮기면
DB 행 잠금 앞의 줄 서기가 사라져 도착률이 올라가도 p95가 완만하게 증가한다.
DB에는 발급 이력만 기록하므로 coupon 행 갱신 경합이 발급 경로에서 제거된다.
```

Redis가 재고를 결정하고 DB가 이력을 저장하면 두 저장소가 어긋날 수 있다. 이 문제는 로드맵의 "실험: Redis 성공 후 DB 실패" 단계에서 별도로 재현한다.

## 변경

아직 없음. 문제를 재현하고 가설을 확정한 뒤에 설계한다.

## 결과

아직 없음.

## 판단

아직 없음.
