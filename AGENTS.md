# CouponService Agent Instructions

이 파일은 저장소에서 작업할 때 가장 먼저 읽는 짧은 안내서다. 상세 규칙은 아래 문서를 따른다.

## 먼저 읽을 문서

1. [README.md](README.md): 프로젝트 목적, 요구사항, 단계별 로드맵
2. [V1 API 계약](docs/v1/api.md): 요청·응답 JSON, 상태 코드, 오류 형식
3. [V1 개발 가이드](docs/v1/development-guide.md): 계층 책임, 도메인, JPA, Validation, 트랜잭션 규칙
4. [Java 코드 컨벤션](docs/conventions/java.md): 명명, 메서드 책임, 변수, 주석 규칙

API 또는 DTO를 변경할 때는 `docs/v1/api.md`를 먼저 확인하고, 계약이 달라지면 문서를 코드보다 먼저 수정한다.

## 핵심 작업 규칙

- 현재 범위는 V1 동기 발급이다. 문제 재현 전에는 비관적 락, Atomic UPDATE, Redis, Kafka를 발급 흐름에 도입하지 않는다.
- 사용자가 직접 코드를 작성하는 학습 흐름에서는 완성 코드를 대신 작성하지 않는다. 원칙과 작은 힌트를 제공하고 작성된 코드를 검토한다.
- 한 번에 하나의 파일 또는 하나의 책임에 집중한다.
- 사용자가 요청하지 않으면 빌드와 테스트를 실행하지 않는다.
- 기존 사용자 변경사항을 보존하고 관련 없는 파일을 수정하지 않는다.
- Entity, Service, Repository, Controller, DTO의 책임을 섞지 않는다.
- 엔티티를 API 요청이나 응답에 직접 노출하지 않는다.
- 전체 Setter와 Lombok `@Data`를 엔티티에 사용하지 않는다.
- 비밀정보는 환경변수로 관리하고 `.env`를 읽어 출력하거나 커밋하지 않는다.

## 버전별 방향

상세 내용은 `README.md`의 단계별 로드맵을 정본으로 삼는다. 아래 순서를 건너뛰지 않는다.

| 단계 | 방향 |
|---|---|
| V1 | REST + JPA + MariaDB로 단순 동기 발급과 트랜잭션 구현 |
| V2 | 비관적 락으로 Row Lock, Lock Wait, Deadlock, Connection 점유 관찰 |
| V3 | 조건부 Atomic UPDATE를 적용하고 비관적 락과 비교 |
| V4 | Redis 원자 연산과 Lua로 DB 경합 감소 |
| 실험 | Redis 성공 후 DB 실패를 재현해 Dual Write와 보상 처리 검토 |
| V5 | Kafka 비동기 발급으로 Producer/Consumer 분리와 트래픽 완충 |
| V6 | `eventId`와 UNIQUE 제약으로 메시지 처리 멱등성 확보 |
| V7 | Retry, DLT, Replay로 Consumer 장애 복구 |
| V8 | Redis 예약 결과와 DB 발급 결과 Reconciliation |
| V9 | Cache Aside, TTL, Stale Data, Cache Stampede 검증 |
| V10 | SOLD_OUT Flag로 매진 후 불필요한 Redis 연산 제거 |
| V11 | 사용자·클라이언트별 Rate Limit 적용 |
| V12 | Virtual Waiting Room으로 시스템 전체 유입량 제어 |

각 단계는 `문제 -> 가설 -> 변경 -> 결과 -> 판단`을 기록한 뒤 다음 단계로 진행한다.

## 문서 우선순위

- 사용자의 명시적인 요청
- 향후 추가될 OpenAPI 계약
- `docs/v1/api.md`
- `docs/v1/development-guide.md`
- `docs/conventions/java.md`
- `README.md`

문서와 코드가 다르면 임의로 한쪽을 정답으로 가정하지 말고 차이를 사용자에게 알린다.
