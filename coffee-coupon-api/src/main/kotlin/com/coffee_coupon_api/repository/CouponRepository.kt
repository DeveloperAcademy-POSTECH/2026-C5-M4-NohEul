package com.coffee_coupon_api.repository

import com.coffee_coupon_api.domain.Coupon
import org.springframework.data.jpa.repository.JpaRepository

interface CouponRepository : JpaRepository<Coupon, Long>
