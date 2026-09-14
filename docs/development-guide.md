# Development Guide

이 문서는 모든 구현 버전에 공통으로 적용되는 계층, 도메인, JPA, Validation, 트랜잭션, 예외 처리 규칙을 설명한다.
버전마다 달라지는 발급 방식과 실험은 `docs/vN/development-guide.md`에 둔다.

## 공통 원칙

- MariaDB를 최종 Source of Truth로 사용한다.
- 직전 단계의 문제를 재현하기 전에는 다음 단계의 기술을 발급 흐름에 도입하지 않는다.
- Batch, Cache, Redis, Kafka는 해당 학습 단계 전에는 실제 흐름에 연결하지 않는다.
- 외부 API 계약은 [API 계약](api.md)을 따르며 구현 버전이 바뀌어도 유지한다.

## 계층별 책임

### Controller

- HTTP 경로, 상태 코드, `@Valid`, Path Variable, Request Body를 처리한다.
- Service를 호출하고 결과를 응답 DTO로 변환한다.
- 비즈니스 규칙이나 직접적인 JPA 조회를 작성하지 않는다.
- 엔티티를 HTTP 응답으로 직접 반환하지 않는다.

### Service

- 하나의 유스케이스를 순서대로 조율하고 트랜잭션 경계를 설정한다.
- Repository 조회와 도메인 메서드를 조합한다.
- `ResponseEntity`, `HttpStatus` 등 HTTP 타입에 의존하지 않는다.
- DB 조회가 필요한 존재 여부와 중복 사전 검사를 담당한다.

### Entity

- 자신의 상태와 비즈니스 불변식을 보호한다.
- 전체 Setter와 Lombok `@Data`를 사용하지 않는다.
- JPA 기본 생성자는 `protected`로 제한한다.
- 의미 있는 정적 팩토리 메서드로 생성한다.
- Repository와 HTTP 타입에 의존하지 않는다.

### Repository

- 저장과 조회만 담당한다.
- 단순 조건은 Spring Data 파생 쿼리를 우선 사용한다.
- 복잡한 엔티티 조회에는 JPQL을 사용하고 DB 전용 기능이 필요할 때만 Native SQL을 고려한다.
- `JpaRepository`를 상속한 인터페이스의 `@Repository`는 선택 사항이다.
- 중첩 프로퍼티 `CouponIssue.coupon.id`는 파생 쿼리에서 `Coupon_Id`로 명확히 구분한다.
- 필요한 시점에만 조회 메서드를 추가한다. 특정 버전 전용 메서드는 그 버전 문서에서 설명한다.

### DTO

- [API 계약](api.md)에 정의된 필드만 가진다.
- 요청과 응답 DTO를 각각 `dto/request`, `dto/response`에 둔다.
- 가능하면 Java `record`를 사용한다.
- 요청 DTO는 입력 형식을 검증하고 응답 DTO는 엔티티를 노출하지 않는다.

## 도메인 규칙

### Coupon

- `Coupon.create()`로만 생성한다.
- 이름은 비어 있을 수 없다.
- 전체 수량은 1 이상이어야 한다.
- `startAt`은 `endAt`보다 이전이어야 한다.
- `Coupon.issue(issuedAt)`가 발급 기간과 재고를 검증하고 잔여 수량을 1 감소시킨다.
- 검증만 하는 메서드는 상태를 바꾸지 않는다. `validate...()`는 재고를 감소시키지 않는다.
- 현재 발급 기간 경계는 시작과 종료 시각을 포함한다.

### CouponIssue

- 특정 사용자에게 쿠폰 한 장이 발급된 사건을 나타낸다.
- 여러 발급 이력이 쿠폰 하나를 참조하므로 `ManyToOne`을 사용한다.
- 연관관계는 `CouponIssue -> Coupon` 단방향으로 유지한다.
- `ManyToOne`은 `fetch = LAZY`, `optional = false`를 사용한다.
- `coupon_id`는 `nullable = false` 외래 키다.
- `(coupon_id, user_id)`에는 이름이 있는 복합 UNIQUE 제약을 둔다.
- `CouponIssue.create()`가 coupon, userId, issuedAt의 기본 불변식을 확인한다.
- 사용자 존재 여부와 중복 발급 여부를 Entity에서 Repository로 검사하지 않는다.

## Validation과 무결성

```text
Request DTO -> 입력 형식
Entity      -> 도메인 불변식
Service     -> DB 조회가 필요한 규칙
Database    -> 최종 데이터 무결성
```

- 필수 값에는 `@NotNull` 또는 `@NotBlank`를 사용한다.
- 양수 ID와 수량에는 `@Positive`를 사용한다.
- 문자열 길이는 DB 컬럼에 맞춰 `@Size`를 사용한다.
- Controller의 Request Body에는 `@Valid`를 붙인다.
- 기간 비교, 발급 기간, 재고 같은 상태 기반 규칙은 도메인 메서드에서 검사한다.
- 쿠폰 존재 여부는 Service가 Repository 조회로 확인한다.
- `COUPON_NOT_FOUND`는 Repository 조회 실패를 나타내며 단순 null 전달과 혼동하지 않는다.
- 사용자 ID 형식과 실제 사용자 존재 여부를 구분한다.
- 사용자 도메인이 생기기 전에는 `Long userId`를 저장하고, 생긴 뒤에는 Service에서 존재 여부를 확인한다.
- 서비스의 중복 사전 조회는 친절한 오류를 위한 것이며 DB UNIQUE 제약을 대체하지 않는다.

## 발급 트랜잭션 공통 규칙

발급 방식은 버전마다 다르지만 다음은 모든 버전에서 지킨다.

- 발급 전체를 하나의 public `@Transactional` Service 메서드로 묶는다. Spring의 `org.springframework.transaction.annotation.Transactional`을 사용한다.
- private 보조 메서드에는 별도의 `@Transactional`을 붙이지 않는다.
- 조회한 `Coupon`은 영속 상태이므로 변경 후 불필요한 `save(coupon)`을 호출하지 않는다.
- 새로 만든 `CouponIssue`는 Repository에 저장한다.
- 발급 이력 저장 실패 시 재고 감소도 함께 롤백되어야 한다.
- 중복 사전 조회를 동시에 통과한 요청은 DB UNIQUE 제약이 최종 차단한다.
- UNIQUE 위반을 중복 발급 오류로 변환하는 것은 예외 처리 계층의 책임이다.
- `spring.jpa.open-in-view=false`를 유지하고 필요한 연관 데이터는 트랜잭션 안에서 DTO로 변환하거나 명시적으로 조회한다.

## 구현 버전 선택

모든 발급 구현은 `CouponIssueUseCase`를 구현하며 다음 설정으로 하나만 활성화한다.

```properties
coupon.issue.version=v2
```

- 구현 클래스는 `coupon.service.vN.VNCouponIssueService`에 두고 `@ConditionalOnProperty`로 설정값과 연결한다.
- 내부 구현 버전만 바뀌며 외부 API URL과 응답 계약은 변경하지 않는다.
- 공통 Entity, DTO, 예외와 변경되지 않는 Repository는 버전 없이 유지한다.

## 예외 처리

- 예상 가능한 도메인 오류는 `BusinessException` 계층으로 표현한다.
- 쿠폰 오류는 문자열 대신 `CouponErrorCode`로 식별한다.
- `GlobalExceptionHandler`가 `BusinessException`을 HTTP 상태와 `ErrorResponse`로 변환한다.
- Validation 오류도 `code`, `message` 형태를 유지한다.
- 내부 스택 트레이스나 DB 메시지를 클라이언트에게 노출하지 않는다.

## 코드 스타일

- Java 21을 사용한다.
- 생성자 주입을 사용하고 필요한 경우 Lombok `@RequiredArgsConstructor`를 사용한다.
- 변수와 메서드 이름은 역할을 드러내게 작성한다. 상세 규칙은 [Java 코드 컨벤션](conventions/java.md)을 따른다.
- 주석은 코드 자체보다 필요한 이유를 설명한다.
- 사용되지 않는 추상화와 메서드를 미리 만들지 않는다.
- 비밀정보는 환경변수로 주입하고 `.env`를 출력하거나 커밋하지 않는다.
