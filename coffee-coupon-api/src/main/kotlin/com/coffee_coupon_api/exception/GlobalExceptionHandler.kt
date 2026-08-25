package com.coffee_coupon_api.exception

import com.coffee_coupon_api.dto.ErrorResponse
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(CouponNotFoundException::class)
    fun handleNotFound(ex: CouponNotFoundException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse("COUPON_NOT_FOUND", ex.message ?: "Coupon not found"))

    @ExceptionHandler(CouponSoldOutException::class)
    fun handleSoldOut(ex: CouponSoldOutException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse("COUPON_SOLD_OUT", ex.message ?: "Coupon sold out"))

    @ExceptionHandler(DuplicateIssueException::class)
    fun handleDuplicate(ex: DuplicateIssueException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse("DUPLICATE_ISSUE", ex.message ?: "Already issued"))

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleDataIntegrityViolation(ex: DataIntegrityViolationException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse("DUPLICATE_ISSUE", "Already issued"))

    @ExceptionHandler(CouponNotYetOpenException::class)
    fun handleNotYetOpen(ex: CouponNotYetOpenException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(ErrorResponse("COUPON_NOT_YET_OPEN", ex.message ?: "Coupon not yet open"))

    @ExceptionHandler(CouponIssueConflictException::class)
    fun handleIssueConflict(ex: CouponIssueConflictException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse("COUPON_ISSUE_CONFLICT", ex.message ?: "Concurrent modification conflict"))
}
