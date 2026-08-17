package com.coffee_coupon_api.repository

import com.coffee_coupon_api.domain.CouponTemplate
import org.springframework.data.jpa.repository.JpaRepository

interface CouponTemplateRepository : JpaRepository<CouponTemplate, Long>
