package com.coffee_coupon_api.service

import com.coffee_coupon_api.domain.CouponCampaign
import com.coffee_coupon_api.domain.CouponIssue
import com.coffee_coupon_api.domain.CouponTemplate
import com.coffee_coupon_api.exception.CouponNotFoundException
import com.coffee_coupon_api.exception.CouponNotYetOpenException
import com.coffee_coupon_api.exception.CouponSoldOutException
import com.coffee_coupon_api.exception.DuplicateIssueException
import com.coffee_coupon_api.repository.CouponCampaignRepository
import com.coffee_coupon_api.repository.CouponIssueRepository
import com.coffee_coupon_api.repository.CouponTemplateRepository
import java.time.Clock
import java.time.LocalDateTime
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CouponService(
    private val couponCampaignRepository: CouponCampaignRepository,
    private val couponTemplateRepository: CouponTemplateRepository,
    private val couponIssueRepository: CouponIssueRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
) {

    @Transactional
    fun issue(couponCampaignId: Long, userId: Long): CouponIssue {
        val openAt = couponCampaignRepository.findOpenAtById(couponCampaignId)
            ?: throw CouponNotFoundException(couponCampaignId)

        if (LocalDateTime.now(clock).isBefore(openAt)) {
            throw CouponNotYetOpenException(couponCampaignId, openAt)
        }

        val campaign = couponCampaignRepository.findByIdForUpdate(couponCampaignId)
            .orElseThrow { CouponNotFoundException(couponCampaignId) }

        if (couponIssueRepository.existsByCouponCampaignIdAndUserId(couponCampaignId, userId)) {
            throw DuplicateIssueException(couponCampaignId, userId)
        }

        if (campaign.issuedQuantity >= campaign.totalQuantity) {
            throw CouponSoldOutException(couponCampaignId)
        }

        campaign.issuedQuantity += 1
        couponCampaignRepository.save(campaign)

        return couponIssueRepository.save(CouponIssue(couponCampaignId = couponCampaignId, userId = userId))
    }

    @Transactional(readOnly = true)
    fun getCoupon(couponCampaignId: Long): Pair<CouponCampaign, CouponTemplate> {
        val campaign = couponCampaignRepository.findById(couponCampaignId)
            .orElseThrow { CouponNotFoundException(couponCampaignId) }
        val template = couponTemplateRepository.findById(campaign.couponTemplateId)
            .orElseThrow { CouponNotFoundException(campaign.couponTemplateId) }
        return campaign to template
    }
}
