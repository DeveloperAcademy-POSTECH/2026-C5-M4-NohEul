package com.coffee_coupon_api.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

data class CouponIssueRequest(
    @field:Schema(description = "발급받을 사용자 ID", example = "1")
    val userId: Long,
)

data class CouponIssueResponse(
    @field:Schema(description = "쿠폰 ID", example = "1")
    val couponId: Long,
    @field:Schema(description = "발급받은 사용자 ID", example = "1")
    val userId: Long,
    @field:Schema(description = "발급 시각", example = "2026-08-12T10:00:00")
    val issuedAt: LocalDateTime,
)

data class CouponResponse(
    @field:Schema(description = "쿠폰 ID", example = "1")
    val id: Long,
    @field:Schema(description = "쿠폰 이름", example = "아메리카노 무료 쿠폰")
    val name: String,
    @field:Schema(description = "총 발급 가능 수량", example = "100")
    val totalQuantity: Int,
    @field:Schema(description = "현재까지 발급된 수량", example = "42")
    val issuedQuantity: Int,
    @field:Schema(description = "남은 수량", example = "58")
    val remainingQuantity: Int,
)
