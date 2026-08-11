package com.coffee_coupon_api.exception

class CouponNotFoundException(couponId: Long) : RuntimeException("Coupon not found: $couponId")

class CouponSoldOutException(couponId: Long) : RuntimeException("Coupon sold out: $couponId")

class DuplicateIssueException(couponId: Long, userId: Long) :
    RuntimeException("Coupon $couponId already issued to user $userId")
