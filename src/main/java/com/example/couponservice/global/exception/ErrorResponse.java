package com.example.couponservice.global.exception;

// Java 예외를 Client에게 반환할 JSON 응답 형식으로 변환한다
public record ErrorResponse (
        String code,
        String message
) {
}
