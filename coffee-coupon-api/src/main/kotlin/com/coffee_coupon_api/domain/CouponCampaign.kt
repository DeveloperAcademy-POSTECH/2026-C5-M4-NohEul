package com.coffee_coupon_api.domain

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Version
import java.time.LocalDateTime

@Entity
class CouponCampaign(
    var couponTemplateId: Long,
    var totalQuantity: Int,
    var issuedQuantity: Int = 0,
    var openAt: LocalDateTime = LocalDateTime.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Version
    var version: Long = 0
}
