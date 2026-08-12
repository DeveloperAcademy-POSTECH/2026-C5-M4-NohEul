package com.coffee_coupon_api.dto

import io.swagger.v3.oas.annotations.media.Schema

data class ErrorResponse(
    @field:Schema(description = "에러 코드", example = "COUPON_NOT_FOUND")
    val code: String,
    @field:Schema(description = "에러 메시지", example = "Coupon not found: 1")
    val message: String,
)
