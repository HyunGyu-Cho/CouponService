package com.example.couponservice.global.exception;

public record ErrorResponse(
        String code,
        String message
) {
}
