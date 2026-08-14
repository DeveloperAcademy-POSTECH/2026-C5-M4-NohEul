package com.coffee_coupon_api.domain

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.LocalDateTime

@Entity
@Table(
    name = "coupon_issue",
    uniqueConstraints = [UniqueConstraint(columnNames = ["coupon_campaign_id", "user_id"])],
)
class CouponIssue(
    var couponCampaignId: Long,
    var userId: Long,
    var issuedAt: LocalDateTime = LocalDateTime.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}
