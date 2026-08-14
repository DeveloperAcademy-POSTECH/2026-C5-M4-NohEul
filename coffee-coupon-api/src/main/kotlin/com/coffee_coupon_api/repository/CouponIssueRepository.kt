package com.coffee_coupon_api.repository

import com.coffee_coupon_api.domain.CouponIssue
import org.springframework.data.jpa.repository.JpaRepository

interface CouponIssueRepository : JpaRepository<CouponIssue, Long> {
    fun existsByCouponCampaignIdAndUserId(couponCampaignId: Long, userId: Long): Boolean
}
