package com.coffee_coupon_api.service

import com.coffee_coupon_api.domain.CouponCampaign
import com.coffee_coupon_api.domain.CouponIssue
import com.coffee_coupon_api.exception.CouponNotFoundException
import com.coffee_coupon_api.exception.CouponNotYetOpenException
import com.coffee_coupon_api.exception.CouponSoldOutException
import com.coffee_coupon_api.exception.DuplicateIssueException
import com.coffee_coupon_api.repository.CouponCampaignRepository
import com.coffee_coupon_api.repository.CouponIssueRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

@Service
class CouponIssueAttempter(
    private val couponCampaignRepository: CouponCampaignRepository,
    private val couponIssueRepository: CouponIssueRepository,
    private val clock: Clock = Clock.systemDefaultZone()
) {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    internal fun attemptIssue(couponCampaignId: Long, userId: Long): CouponIssue {
        val campaign = couponCampaignRepository.findById(couponCampaignId)
            .orElseThrow { CouponNotFoundException(couponCampaignId) }
        if (LocalDateTime.now(clock).isBefore(campaign.openAt)) {
            throw CouponNotYetOpenException(couponCampaignId, campaign.openAt)
        }
        return completeIssue(campaign, couponCampaignId, userId)
    }

    internal fun completeIssue(campaign: CouponCampaign, couponCampaignId: Long, userId: Long): CouponIssue {
        ensureNotAlreadyIssued(couponCampaignId, userId)
        ensureStockAvailable(campaign, couponCampaignId)
        incrementIssuedQuantity(campaign)
        return saveIssue(couponCampaignId, userId)
    }

    internal fun ensureNotAlreadyIssued(couponCampaignId: Long, userId: Long) {
        if (couponIssueRepository.existsByCouponCampaignIdAndUserId(couponCampaignId, userId)) {
            throw DuplicateIssueException(couponCampaignId, userId)
        }
    }

    internal fun ensureStockAvailable(campaign: CouponCampaign, couponCampaignId: Long) {
        if (campaign.issuedQuantity >= campaign.totalQuantity) {
            throw CouponSoldOutException(couponCampaignId)
        }
    }

    private fun incrementIssuedQuantity(campaign: CouponCampaign) {
        campaign.issuedQuantity += 1
        couponCampaignRepository.save(campaign)
    }

    internal fun saveIssue(couponCampaignId: Long, userId: Long): CouponIssue {
        return couponIssueRepository.save(CouponIssue(couponCampaignId = couponCampaignId, userId = userId))
    }

}