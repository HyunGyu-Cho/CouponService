# API Contract

이 문서는 쿠폰 API의 요청과 응답 계약이다. 내부 발급 구현이 V1, V2, V3로 바뀌어도 이 계약은 유지하며 URL에 구현 버전을 붙이지 않는다. DTO 또는 Controller를 변경하기 전에 이 문서를 먼저 변경한다.

## 공통 규칙

- 기본 경로는 `/api`다.
- 요청과 응답은 JSON을 사용하고 필드명은 `camelCase`로 작성한다.
- ID는 양수 `Long`이며 JSON number로 표현한다.
- 날짜와 시간은 ISO-8601 문자열을 사용한다.
- `LocalDateTime`은 `Asia/Seoul` 기준 로컬 시간으로 해석한다.
- 여러 시간대를 지원하게 되면 `2026-08-30T15:00:00+09:00`처럼 시간대 차이를 포함하는 형식으로 계약을 변경한다.
- 엔티티를 Request Body 또는 Response Body로 직접 사용하지 않는다.
- 성공 응답에는 별도의 공통 envelope를 두지 않는다.
- 보유 쿠폰이 없으면 `404`가 아니라 빈 배열과 `200 OK`를 반환한다.

## 공통 오류 응답

```json
{
  "code": "COUPON_010",
  "message": "이미 발급받은 쿠폰입니다."
}
```

주요 상태 코드:

```text
400 Bad Request -> 입력 형식 오류
404 Not Found   -> 쿠폰을 찾을 수 없음
409 Conflict    -> 발급 시작 전, 만료, 매진, 중복 발급
500 Internal Server Error -> 예상하지 못한 서버 오류
```

Validation 오류도 위와 동일한 `code`, `message` 형태를 사용한다.

요청 값 검증 실패:

```json
{
  "code": "INVALID_REQUEST",
  "message": "요청 값이 올바르지 않습니다."
}
```

`@Valid`, `@NotNull`, `@NotBlank`, `@Positive` 등의 입력 검증 실패에는 `400 Bad Request`와 `INVALID_REQUEST`를 사용한다. 읽을 수 없는 JSON, 잘못된 날짜 형식과 Path Variable 타입 오류에도 같은 응답을 사용한다.

### 오류 노출 원칙

- 사용자가 다음 행동을 결정하는 데 필요한 비즈니스 결과만 `code`, `message`로 전달한다.
- SQL, 테이블명, 제약 조건명, Java 예외명, Stack Trace와 서버 경로는 응답에 포함하지 않는다.
- 예상하지 못한 오류는 상세 원인을 서버 로그에만 남기고 사용자에게는 일반적인 문구를 반환한다.

```json
{
  "code": "INTERNAL_SERVER_ERROR",
  "message": "요청을 처리하는 중 오류가 발생했습니다."
}
```

사용자에게 전달할 수 있는 쿠폰 오류:

| 코드 | 상태 | 안전한 사용자 안내 |
|---|---:|---|
| `COUPON_001` | 400 | 쿠폰 이름은 비어 있을 수 없습니다. |
| `COUPON_002` | 400 | 쿠폰 전체 수량은 1개 이상이어야 합니다. |
| `COUPON_003` | 400 | 쿠폰 발급 기간이 올바르지 않습니다. |
| `COUPON_005` | 409 | 쿠폰 발급 기간이 시작되지 않았습니다. |
| `COUPON_006` | 409 | 쿠폰 발급 기간이 종료되었습니다. |
| `COUPON_007` | 409 | 쿠폰 재고가 모두 소진되었습니다. |
| `COUPON_008` | 404 | 쿠폰을 찾을 수 없습니다. |
| `COUPON_009` | 400 | 사용자 ID는 1 이상이어야 합니다. |
| `COUPON_010` | 409 | 이미 발급받은 쿠폰입니다. |

`COUPON_004`는 서버가 생성하는 발급 시각이 누락된 내부 상태를 의미하므로 정상 API 흐름에서 사용자에게 직접 노출하지 않는다.

## 쿠폰 생성

```http
POST /api/coupons
Content-Type: application/json
```

요청:

```json
{
  "name": "신규 가입 10% 할인",
  "totalQuantity": 100,
  "startAt": "2026-08-30T10:00:00",
  "endAt": "2026-08-31T10:00:00"
}
```

성공: `201 Created`

```json
{
  "couponId": 1,
  "name": "신규 가입 10% 할인",
  "totalQuantity": 100,
  "remainingQuantity": 100,
  "startAt": "2026-08-30T10:00:00",
  "endAt": "2026-08-31T10:00:00",
  "createdAt": "2026-08-30T09:00:00"
}
```

## 쿠폰 단건 조회

```http
GET /api/coupons/{couponId}
```

성공: `200 OK`

응답은 쿠폰 생성 성공 응답과 동일한 필드를 사용한다.

`remainingQuantity`는 DB에 저장된 값이다. V1~V3에서는 실시간 재고와 같다. V4 Redis 발급부터는 실시간 재고가 Redis에 있고 DB 값은 발급 경로에서 갱신하지 않으므로, 이 필드는 최신이 아닐 수 있다. 실시간 값을 응답에 반영하는 방식은 V8 대사 또는 V9 캐시 단계에서 이 문서에 먼저 정의한다.

## 쿠폰 발급

```http
POST /api/coupons/{couponId}/issue
Content-Type: application/json
```

요청:

```json
{
  "userId": 100
}
```

현재 인증 기능이 없으므로 `userId`를 Request Body로 받는다. 인증 기능이 추가되면 로그인 정보에서 사용자 ID를 확인하고 이 필드는 요청에서 제거한다.

성공: `201 Created`

```json
{
  "issueId": 1,
  "couponId": 10,
  "userId": 100,
  "issuedAt": "2026-08-30T15:00:00"
}
```

## 사용자별 보유 쿠폰 조회

```http
GET /api/users/{userId}/coupons
```

성공: `200 OK`

```json
{
  "userId": 100,
  "coupons": [
    {
      "issueId": 1,
      "couponId": 10,
      "name": "신규 가입 10% 할인",
      "expiresAt": "2026-08-31T10:00:00",
      "issuedAt": "2026-08-30T15:00:00"
    }
  ]
}
```

현재는 단순화를 위해 쿠폰의 발급 종료 시각인 `Coupon.endAt`을 보유 쿠폰의 `expiresAt`으로도 사용한다. 발급 기간과 실제 사용 기간이 달라지면 별도의 만료 정책과 데이터 필드를 정의한다.

페이지네이션이 필요해지면 쿼리 파라미터와 응답 메타데이터를 이 문서에 먼저 추가한다.

## 이후 버전

- 동기 발급(V1~V3)은 발급 이력 저장 후 `201 Created`를 반환한다.
- Kafka 비동기 발급에서 DB 저장 전에 응답한다면 `202 Accepted`와 요청 또는 이벤트 ID를 반환하는 별도 계약을 정의한다.
- 비동기 계약을 동기 응답에 조용히 덮어쓰지 않는다.
