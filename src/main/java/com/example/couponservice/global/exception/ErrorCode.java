package com.example.couponservice.global.exception;

import org.springframework.http.HttpStatus;

public interface ErrorCode {
    // HTTP 상태 코드
    HttpStatus getHttpStatus();

    // 어플리케이션이 정의한 오류코드
    String getCode();

    // 사용자에게 전달할 메세지
    String getMessage();
}
