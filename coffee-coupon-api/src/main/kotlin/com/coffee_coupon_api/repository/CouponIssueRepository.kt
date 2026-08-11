package com.coffee_coupon_api.repository

import com.coffee_coupon_api.domain.CouponIssue
import org.springframework.data.jpa.repository.JpaRepository

interface CouponIssueRepository : JpaRepository<CouponIssue, Long> {
    fun existsByCouponIdAndUserId(couponId: Long, userId: Long): Boolean
}
