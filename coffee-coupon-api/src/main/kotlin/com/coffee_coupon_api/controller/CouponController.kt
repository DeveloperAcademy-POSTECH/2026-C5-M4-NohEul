package com.coffee_coupon_api.controller

import com.coffee_coupon_api.domain.CouponIssue
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

    @Operation(
        summary = "쿠폰 발급 (비관적 락)",
        description = "지정한 쿠폰을 사용자에게 발급한다. `SELECT ... FOR UPDATE`로 캠페인 행에 락을 걸어 동시 요청에도 재고를 초과 발급하지 않는다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "발급 성공"),
        ApiResponse(
            responseCode = "403",
            description = "아직 오픈되지 않은 쿠폰(COUPON_NOT_YET_OPEN)",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
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
    @PostMapping("/{couponId}/issue-pessimistic")
    fun issuePessimistic(
        @Parameter(description = "발급할 쿠폰 ID") @PathVariable couponId: Long,
        @RequestBody request: CouponIssueRequest,
    ): CouponIssueResponse {
        return couponService.issuePessimistic(couponId, request.userId).toResponse()
    }

    @Operation(
        summary = "쿠폰 발급 (락 없음, 대조군)",
        description = "지정한 쿠폰을 사용자에게 발급한다. 동시성 제어를 전혀 하지 않아, 동시 요청 시 재고를 초과해서 발급될 수 있다 " +
            "— 락 전략 비교의 대조군으로 의도적으로 남겨둔 엔드포인트다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "발급 성공"),
        ApiResponse(
            responseCode = "403",
            description = "아직 오픈되지 않은 쿠폰(COUPON_NOT_YET_OPEN)",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
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
    @PostMapping("/{couponId}/issue-no-lock")
    fun issueNoLock(
        @Parameter(description = "발급할 쿠폰 ID") @PathVariable couponId: Long,
        @RequestBody request: CouponIssueRequest,
    ): CouponIssueResponse {
        return couponService.issueNoLock(couponId, request.userId).toResponse()
    }

    @Operation(
        summary = "쿠폰 발급 (낙관적 락)",
        description = "지정한 쿠폰을 사용자에게 발급한다. `@Version` 기반으로 커밋 시점 충돌을 감지하고, " +
            "충돌 시 최대 3회까지 재조회 후 재시도한다. 재시도를 다 써도 충돌하면 409를 반환한다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "발급 성공"),
        ApiResponse(
            responseCode = "403",
            description = "아직 오픈되지 않은 쿠폰(COUPON_NOT_YET_OPEN)",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "쿠폰을 찾을 수 없음",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "쿠폰 소진(COUPON_SOLD_OUT), 중복 발급(DUPLICATE_ISSUE), " +
                "또는 재시도 소진으로 인한 동시 수정 충돌(COUPON_ISSUE_CONFLICT)",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @PostMapping("/{couponId}/issue-optimistic")
    fun issueOptimistic(
        @Parameter(description = "발급할 쿠폰 ID") @PathVariable couponId: Long,
        @RequestBody request: CouponIssueRequest,
    ): CouponIssueResponse {
        return couponService.issueOptimistic(couponId, request.userId).toResponse()
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

private fun CouponIssue.toResponse() = CouponIssueResponse(
    couponId = couponCampaignId,
    userId = userId,
    issuedAt = issuedAt,
)
