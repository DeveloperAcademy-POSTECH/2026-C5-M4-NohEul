package com.coffee_coupon_api.exception

import java.time.LocalDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus

class GlobalExceptionHandlerTest {

    private val handler = GlobalExceptionHandler()

    @Test
    fun `쿠폰을 찾을 수 없으면 404를 반환한다`() {
        val response = handler.handleNotFound(CouponNotFoundException(1L))

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertEquals("COUPON_NOT_FOUND", response.body?.code)
    }

    @Test
    fun `재고가 소진되면 409를 반환한다`() {
        val response = handler.handleSoldOut(CouponSoldOutException(1L))

        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        assertEquals("COUPON_SOLD_OUT", response.body?.code)
    }

    @Test
    fun `중복 발급이면 409를 반환한다`() {
        val response = handler.handleDuplicate(DuplicateIssueException(1L, 1L))

        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        assertEquals("DUPLICATE_ISSUE", response.body?.code)
    }

    @Test
    fun `중복 저장으로 인한 무결성 위반이면 409를 반환한다`() {
        val response = handler.handleDataIntegrityViolation(DataIntegrityViolationException("test"))

        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        assertEquals("DUPLICATE_ISSUE", response.body?.code)
    }

    @Test
    fun `오픈 전 발급 요청이면 403을 반환한다`() {
        val response = handler.handleNotYetOpen(CouponNotYetOpenException(1L, LocalDateTime.of(2026, 8, 14, 10, 0)))

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
        assertEquals("COUPON_NOT_YET_OPEN", response.body?.code)
    }
}
