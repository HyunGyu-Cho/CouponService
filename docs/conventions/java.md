# Java Code Convention

이 문서는 모든 버전에 공통으로 적용되는 Java 명명 및 코드 작성 규칙이다. 이름만 읽어도 책임, 반환값, 부작용을 예측할 수 있게 하는 것을 우선한다.

## 메서드 이름

기본 구조는 `동사 + 대상 + 조건`이다.

```java
issueCoupon(couponId, userId)
getCouponById(couponId)
validateIssuablePeriod(issuedAt)
existsByCoupon_IdAndUserId(couponId, userId)
```

- 메서드는 동사로 시작한다.
- 같은 동사는 프로젝트 전체에서 같은 의미로 사용한다.
- `process`, `handle`, `execute`, `check`, `run`, `manage`처럼 대상을 알 수 없는 이름을 단독으로 사용하지 않는다.
- 구현 방식보다 비즈니스 의도를 표현한다. `insertCouponIssueRow()`보다 `issueCoupon()`을 사용한다.
- 메서드 이름에 여러 작업을 나열해야 한다면 책임 분리를 검토한다.

## 동사의 의미

| 동사 | 의미 |
|---|---|
| `create` | 새로운 객체 또는 자원 생성 |
| `issue` | 쿠폰 발급 행위 수행 |
| `find` | 값이 없을 수 있는 조회, 주로 `Optional` 반환 |
| `get` | 반드시 존재해야 하는 조회, 없으면 예외 |
| `exists` | 저장소의 존재 여부를 `boolean`으로 반환 |
| `is` | 현재 상태를 `boolean`으로 반환 |
| `has` | 어떤 값이나 상태를 보유하는지 반환 |
| `can` | 특정 행위가 가능한지 반환 |
| `validate` | 위반 시 예외, 성공 시 반환값 없음 |
| `calculate` | 상태 변경 없이 값 계산 |
| `increase`, `decrease` | 수치 상태 변경 |
| `save` | 저장소에 영속화 |
| `delete` | 데이터 제거 |
| `from` | 객체 하나를 바탕으로 현재 타입 생성 |
| `of` | 여러 값을 조합해 현재 타입 생성 |
| `to` | 다른 표현으로 변환 |

## 조회 이름

`find`와 `get`의 의미를 구분한다.

```java
Optional<Coupon> findCouponById(Long couponId);
Coupon getCouponById(Long couponId); // 없으면 예외
```

Repository는 Spring Data 규칙에 따라 `findBy...`, `existsBy...`를 사용한다. 중첩 프로퍼티는 필요하면 `_`로 구분한다.

```java
boolean existsByCoupon_IdAndUserId(Long couponId, Long userId);
```

## Boolean 이름

- 질문처럼 읽히도록 `is`, `has`, `can`, `exists`를 사용한다.
- Boolean 변수는 긍정문으로 작성하고 이중 부정을 피한다.

```java
boolean alreadyIssued;
boolean soldOut;
boolean hasRemainingQuantity();
boolean canIssueAt(LocalDateTime issuedAt);
```

```java
boolean isNotDuplicated; // 피한다
```

## 계층별 이름

### Controller와 Service

유스케이스가 드러나는 이름을 사용한다.

```java
createCoupon()
getCoupon()
issueCoupon()
getIssuedCouponsByUserId()
```

Service의 public 메서드는 유스케이스를 나타내고 private 메서드는 하위 의도를 나타낸다.

```java
public CouponIssue issueCoupon(Long couponId, Long userId)
private Coupon getCouponById(Long couponId)
private void validateNotAlreadyIssued(Long couponId, Long userId)
```

### Entity

수신 객체가 이미 대상을 설명하므로 불필요하게 반복하지 않는다.

```java
coupon.issue(issuedAt);
Coupon.create(...);
CouponIssue.create(...);
```

### DTO

목적과 방향을 이름에 포함한다.

```java
CouponCreateRequest
CouponResponse
CouponIssueRequest
CouponIssueResponse
UserCouponResponse
```

## 파라미터와 변수

- `id1`, `value`, `data`, `info`처럼 역할이 불명확한 이름을 피한다.
- 같은 타입의 값이 여러 개라면 대상과 시점을 포함한다.

```java
Long couponId;
Long userId;
LocalDateTime startAt;
LocalDateTime endAt;
LocalDateTime issuedAt;
```

- 컬렉션은 복수형을 사용한다.

```java
List<CouponIssue> couponIssues;
```

## 부작용과 반환값

- `validate...()`는 값을 반환하지 않고 실패 시 예외를 던진다.
- `is...()`, `has...()`, `can...()`은 상태를 변경하지 않는다.
- 상태를 변경하는 메서드는 `issue`, `increase`, `decrease`, `update`, `delete`처럼 부작용이 드러나야 한다.
- 조회 메서드에서 엔티티 상태를 변경하지 않는다.
- 하나의 메서드는 하나의 책임과 하나의 추상화 수준을 유지한다.

## 주석

- 코드가 무엇을 하는지 이름으로 표현하고 주석은 필요한 이유를 설명한다.

```java
// V1에서는 동시성 문제를 재현하기 위해 의도적으로 락 없이 조회한다.
Coupon coupon = getCouponById(couponId);
```

- 메서드 이름과 같은 내용을 반복하는 주석은 제거한다.
- 임시 결정에는 적용 범위와 제거 조건을 함께 기록한다.

## 클래스와 패키지

- 클래스는 하나의 주요 책임만 가진다.
- Spring 구성요소는 역할 접미사를 사용한다: `Controller`, `Service`, `Repository`.
- 요청과 응답 DTO는 각각 `dto.request`, `dto.response`에 둔다.
- 엔티티에는 전체 Setter와 Lombok `@Data`를 사용하지 않는다.
- 의존성 주입은 생성자 주입을 사용한다.

## 구현 버전

- 버전은 소문자 패키지와 대문자 클래스 접두사로 표현한다.

```text
coupon.service.v1.V1CouponIssueService
coupon.service.v2.V2CouponIssueService
```

- Java 식별자에 `V1_CouponService`와 같은 밑줄을 사용하지 않는다.
- 메서드 이름에는 버전을 반복하지 않는다. `issueCouponV1()`이 아니라 `V1CouponIssueService.issueCoupon()`을 사용한다.
- 모든 코드를 복제하지 않고 발급 방식처럼 실제로 변경되는 구현만 버전화한다.
- 공통 Entity, DTO, 예외와 변경되지 않는 Repository는 버전 없이 유지한다.
- 내부 구현 버전과 외부 API 버전을 구분한다. 구현이 V2가 되어도 API 계약이 같다면 URL에 `/v2`를 추가하지 않는다.
- 각 단계 완료 시 Git 태그로 전체 코드 상태를 보존한다. 예: `v1-baseline`, `v2-pessimistic-lock`.
- 버전별 설계와 실험 결과는 `docs/vN`에 기록한다.

## 공백, 들여쓰기와 줄바꿈

- 들여쓰기는 탭이 아닌 공백 4칸을 사용한다.
- `if`, `for`, `while`, `switch`, `catch` 뒤에는 공백 한 칸을 둔다.
- 메서드명, 클래스명, record 이름과 여는 괄호 사이에는 공백을 두지 않는다.
- 닫는 괄호와 여는 중괄호 사이는 공백 한 칸을 둔다.

```java
if (alreadyIssued) {
    throw new CouponException(CouponErrorCode.DUPLICATE_ISSUE);
}

public record CouponIssueRequest(
        Long userId
) {
}
```

- 한 줄이 길어지면 메서드 체이닝과 인자를 의미 단위로 줄바꿈한다.
- 여러 줄 파라미터는 한 줄에 하나씩 두고 닫는 괄호를 별도 줄에 둔다.
- 연속된 필드, 생성자, public 메서드, private 메서드 사이에는 빈 줄 하나를 둔다.
- 불필요한 연속 빈 줄과 줄 끝 공백을 남기지 않는다.
- import는 일반적으로 프로젝트 타입, 외부 라이브러리, `java.*` 그룹 순으로 두고 그룹 사이를 한 줄 띄운다.

## 테스트 이름

- 테스트 이름은 동작과 조건 또는 결과가 드러나는 lowerCamelCase를 사용한다.
- `success`, `fail`, `test1`처럼 결과를 구체적으로 설명하지 않는 단어를 피한다.
- 정상 동작은 `creates...`, `decreases...`, `keeps...`처럼 실제 결과를 표현한다.
- 예외 검증은 `throws오류When조건` 형태를 우선한다.

```java
createsCouponWithInitialRemainingQuantity()
throwsInvalidNameWhenCouponNameIsBlank()
throwsSoldOutWhenNoQuantityRemains()
```

## 코드 검토 체크리스트

- 메서드 이름만 보고 작업과 부작용을 설명할 수 있는가?
- 같은 동사가 다른 의미로 사용되지 않았는가?
- `validate`와 Boolean 질문 메서드의 역할이 섞이지 않았는가?
- 파라미터 이름만 보고 같은 타입의 값들을 구분할 수 있는가?
- 모호한 `process`, `handle`, `check`를 더 구체적인 이름으로 바꿀 수 있는가?
- 주석 없이도 정상 흐름을 위에서 아래로 읽을 수 있는가?
- 구현 버전이 패키지와 클래스 이름에 일관되게 표현되었는가?
- 공백, 들여쓰기와 줄바꿈이 프로젝트 규칙과 일치하는가?
