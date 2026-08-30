# V1 API Contract

이 문서는 V1 동기 쿠폰 API의 요청과 응답 계약이다. DTO 또는 Controller를 변경하기 전에 이 문서를 먼저 변경한다.

## 공통 규칙

- 기본 경로는 `/api`다.
- 요청과 응답은 JSON을 사용하고 필드명은 `camelCase`로 작성한다.
- ID는 양수 `Long`이며 JSON number로 표현한다.
- 날짜와 시간은 ISO-8601 문자열을 사용한다.
- V1의 `LocalDateTime`은 `Asia/Seoul` 기준 로컬 시간으로 해석한다.
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

Validation 오류도 위와 동일한 `code`, `message` 형태를 사용한다. Validation 전용 오류 코드를 정할 때는 구현 전에 이 문서를 갱신한다.

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
      "issuedAt": "2026-08-30T15:00:00"
    }
  ]
}
```

페이지네이션이 필요해지면 쿼리 파라미터와 응답 메타데이터를 이 문서에 먼저 추가한다.

## 이후 버전

- V1 동기 발급은 발급 이력 저장 후 `201 Created`를 반환한다.
- Kafka 비동기 발급에서 DB 저장 전에 응답한다면 `202 Accepted`와 요청 또는 이벤트 ID를 반환하는 별도 계약을 정의한다.
- 비동기 계약을 V1 응답에 조용히 덮어쓰지 않는다.
