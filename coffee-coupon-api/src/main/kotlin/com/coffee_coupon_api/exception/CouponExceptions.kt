package com.coffee_coupon_api.exception

import java.time.LocalDateTime

class CouponNotFoundException(couponId: Long) : RuntimeException("Coupon not found: $couponId")

class CouponSoldOutException(couponId: Long) : RuntimeException("Coupon sold out: $couponId")

class DuplicateIssueException(couponId: Long, userId: Long) :
    RuntimeException("Coupon $couponId already issued to user $userId")

class CouponNotYetOpenException(couponId: Long, openAt: LocalDateTime) :
    RuntimeException("Coupon $couponId not yet open. Opens at $openAt")
