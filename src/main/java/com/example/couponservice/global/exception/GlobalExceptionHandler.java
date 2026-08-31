package com.example.couponservice.global.exception;

import com.example.couponservice.coupon.exception.CouponErrorCode;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String COUPON_ISSUE_UNIQUE_CONSTRAINT =
            "uk_coupon_issue_coupon_user";

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(
            BusinessException exception
    ) {
        return createErrorResponseEntity(exception.getErrorCode());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValidException(
            MethodArgumentNotValidException exception
    ) {
        return createErrorResponseEntity(CommonErrorCode.INVALID_REQUEST);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleMethodValidationException(
            HandlerMethodValidationException exception
    ) {
        return createErrorResponseEntity(CommonErrorCode.INVALID_REQUEST);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleHttpMessageNotReadableException(
            HttpMessageNotReadableException exception
    ) {
        return createErrorResponseEntity(CommonErrorCode.INVALID_REQUEST);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentTypeMismatchException(
            MethodArgumentTypeMismatchException exception
    ) {
        return createErrorResponseEntity(CommonErrorCode.INVALID_REQUEST);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolationException(
            DataIntegrityViolationException exception
    ) {
        if (isCouponIssueUniqueConstraintViolation(exception)) {
            return createErrorResponseEntity(CouponErrorCode.DUPLICATE_ISSUE);
        }

        log.error("예상하지 못한 데이터 무결성 오류가 발생했습니다.", exception);
        return createErrorResponseEntity(CommonErrorCode.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(
            Exception exception
    ) {
        log.error("예상하지 못한 오류가 발생했습니다.", exception);
        return createErrorResponseEntity(CommonErrorCode.INTERNAL_SERVER_ERROR);
    }

    private static boolean isCouponIssueUniqueConstraintViolation(
            Throwable exception
    ) {
        Throwable cause = exception;

        while (cause != null) {
            String message = cause.getMessage();

            if (message != null && message.contains(COUPON_ISSUE_UNIQUE_CONSTRAINT)) {
                return true;
            }

            cause = cause.getCause();
        }

        return false;
    }

    private static ResponseEntity<ErrorResponse> createErrorResponseEntity(
            ErrorCode errorCode
    ) {
        ErrorResponse response = new ErrorResponse(
                errorCode.getCode(),
                errorCode.getMessage()
        );

        return ResponseEntity
                .status(errorCode.getHttpStatus())
                .body(response);
    }
}
