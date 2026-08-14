package com.coffee_coupon_api.controller

import com.coffee_coupon_api.dto.CouponIssueRequest
import com.coffee_coupon_api.dto.CouponIssueResponse
import com.coffee_coupon_api.dto.CouponResponse
import com.coffee_coupon_api.dto.ErrorResponse
import com.coffee_coupon_api.service.CouponService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Coupon", description = "쿠폰 발급 및 조회 API")
@RestController
@RequestMapping("/api/coupons")
class CouponController(
    private val couponService: CouponService,
) {

    @Operation(summary = "쿠폰 발급", description = "지정한 쿠폰을 사용자에게 발급한다. 동일 사용자가 같은 쿠폰을 중복 발급받을 수 없다.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "발급 성공"),
        ApiResponse(
            responseCode = "404",
            description = "쿠폰을 찾을 수 없음",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "쿠폰 소진(COUPON_SOLD_OUT) 또는 중복 발급(DUPLICATE_ISSUE)",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @PostMapping("/{couponId}/issue")
    fun issue(
        @Parameter(description = "발급할 쿠폰 ID") @PathVariable couponId: Long,
        @RequestBody request: CouponIssueRequest,
    ): CouponIssueResponse {
        val issue = couponService.issue(couponId, request.userId)
        return CouponIssueResponse(
            couponId = issue.couponCampaignId,
            userId = issue.userId,
            issuedAt = issue.issuedAt,
        )
    }

    @Operation(summary = "쿠폰 조회", description = "쿠폰 ID로 쿠폰의 발급 현황(총 수량/발급 수량/잔여 수량)을 조회한다.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(
            responseCode = "404",
            description = "쿠폰을 찾을 수 없음",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @GetMapping("/{couponId}")
    fun getCoupon(@Parameter(description = "조회할 쿠폰 ID") @PathVariable couponId: Long): CouponResponse {
        val (campaign, template) = couponService.getCoupon(couponId)
        return CouponResponse(
            id = campaign.id!!,
            name = template.name,
            totalQuantity = campaign.totalQuantity,
            issuedQuantity = campaign.issuedQuantity,
            remainingQuantity = campaign.totalQuantity - campaign.issuedQuantity,
        )
    }
}
