package com.coffee_coupon_api.dto

import java.time.LocalDateTime

data class CouponIssueRequest(
    val userId: Long,
)

data class CouponIssueResponse(
    val couponId: Long,
    val userId: Long,
    val issuedAt: LocalDateTime,
)

data class CouponResponse(
    val id: Long,
    val name: String,
    val totalQuantity: Int,
    val issuedQuantity: Int,
    val remainingQuantity: Int,
)
