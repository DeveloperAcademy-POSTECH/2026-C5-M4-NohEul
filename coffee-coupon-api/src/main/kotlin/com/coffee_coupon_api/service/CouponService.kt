package com.coffee_coupon_api.service

import com.coffee_coupon_api.domain.Coupon
import com.coffee_coupon_api.domain.CouponIssue
import com.coffee_coupon_api.exception.CouponNotFoundException
import com.coffee_coupon_api.exception.CouponSoldOutException
import com.coffee_coupon_api.exception.DuplicateIssueException
import com.coffee_coupon_api.repository.CouponIssueRepository
import com.coffee_coupon_api.repository.CouponRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CouponService(
    private val couponRepository: CouponRepository,
    private val couponIssueRepository: CouponIssueRepository,
) {

    @Transactional
    fun issue(couponId: Long, userId: Long): CouponIssue {
        val coupon = couponRepository.findByIdForUpdate(couponId)
            .orElseThrow { CouponNotFoundException(couponId) }

        if (couponIssueRepository.existsByCouponIdAndUserId(couponId, userId)) {
            throw DuplicateIssueException(couponId, userId)
        }

        if (coupon.issuedQuantity >= coupon.totalQuantity) {
            throw CouponSoldOutException(couponId)
        }

        coupon.issuedQuantity += 1
        couponRepository.save(coupon)

        return couponIssueRepository.save(CouponIssue(couponId = couponId, userId = userId))
    }

    @Transactional(readOnly = true)
    fun getCoupon(couponId: Long): Coupon =
        couponRepository.findById(couponId)
            .orElseThrow { CouponNotFoundException(couponId) }
}
