package com.example.couponservice.global.exception;

import org.springframework.web.bind.annotation.RestControllerAdvice;

// 컨트롤러까지 올라온 비즈니스 예외를 가로채 HTTP 응답으로 변환
@RestControllerAdvice
public class GlobalExceptionHandler {
}
