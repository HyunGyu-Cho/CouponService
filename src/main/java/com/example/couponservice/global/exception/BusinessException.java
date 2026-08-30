package com.example.couponservice.global.exception;

// 어플리케이션에서 예상 가능한 비즈니스 오류의 공통 부모 예외
// 예상 가능한 오류 예시
/*
    쿠폰 이름이 잘못됨
    쿠폰 수량이 잘못됨
    쿠폰 재고가 없음
    발급 기간이 아님
    동일한 쿠폰을 중복 발급
*/

import lombok.Getter;

// RuntimeException 상속하므로 메서드에 일일이 throws 선언하지 않아도 된다
@Getter
public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        // 부모인 RuntimeException에 메세지를 전달한다
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

}
