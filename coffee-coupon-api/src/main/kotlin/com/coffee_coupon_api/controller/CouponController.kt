package com.coffee_coupon_api.controller

import com.coffee_coupon_api.dto.CouponIssueRequest
import com.coffee_coupon_api.dto.CouponIssueResponse
import com.coffee_coupon_api.dto.CouponResponse
import com.coffee_coupon_api.service.CouponService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/coupons")
class CouponController(
    private val couponService: CouponService,
) {

    @PostMapping("/{couponId}/issue")
    fun issue(
        @PathVariable couponId: Long,
        @RequestBody request: CouponIssueRequest,
    ): CouponIssueResponse {
        val issue = couponService.issue(couponId, request.userId)
        return CouponIssueResponse(
            couponId = issue.couponId,
            userId = issue.userId,
            issuedAt = issue.issuedAt,
        )
    }

    @GetMapping("/{couponId}")
    fun getCoupon(@PathVariable couponId: Long): CouponResponse {
        val coupon = couponService.getCoupon(couponId)
        return CouponResponse(
            id = coupon.id!!,
            name = coupon.name,
            totalQuantity = coupon.totalQuantity,
            issuedQuantity = coupon.issuedQuantity,
            remainingQuantity = coupon.totalQuantity - coupon.issuedQuantity,
        )
    }
}
