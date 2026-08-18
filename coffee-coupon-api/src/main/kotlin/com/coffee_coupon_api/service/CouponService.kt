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
    fun issuePessimistic(couponCampaignId: Long, userId: Long): CouponIssue {
        val openAt = couponCampaignRepository.findOpenAtById(couponCampaignId)
            ?: throw CouponNotFoundException(couponCampaignId)

        if (LocalDateTime.now(clock).isBefore(openAt)) {
            throw CouponNotYetOpenException(couponCampaignId, openAt)
        }

        val campaign = couponCampaignRepository.findByIdForUpdate(couponCampaignId)
            .orElseThrow { CouponNotFoundException(couponCampaignId) }

        return completeIssue(campaign, couponCampaignId, userId)
    }

    @Transactional
    fun issueNoLock(couponCampaignId: Long, userId: Long): CouponIssue {
        // findById는 행을 잠그지 않아서, completeIssue의 재고 체크~증가 사이에 다른 트랜잭션이 끼어들 수 있다(Lost Update).
        val campaign = couponCampaignRepository.findById(couponCampaignId)
            .orElseThrow { CouponNotFoundException(couponCampaignId) }

        if (LocalDateTime.now(clock).isBefore(campaign.openAt)) {
            throw CouponNotYetOpenException(couponCampaignId, campaign.openAt)
        }

        return completeIssue(campaign, couponCampaignId, userId)
    }

    private fun completeIssue(campaign: CouponCampaign, couponCampaignId: Long, userId: Long): CouponIssue {
        ensureNotAlreadyIssued(couponCampaignId, userId)
        ensureStockAvailable(campaign, couponCampaignId)
        incrementIssuedQuantity(campaign)
        return saveIssue(couponCampaignId, userId)
    }

    private fun ensureNotAlreadyIssued(couponCampaignId: Long, userId: Long) {
        if (couponIssueRepository.existsByCouponCampaignIdAndUserId(couponCampaignId, userId)) {
            throw DuplicateIssueException(couponCampaignId, userId)
        }
    }

    private fun ensureStockAvailable(campaign: CouponCampaign, couponCampaignId: Long) {
        if (campaign.issuedQuantity >= campaign.totalQuantity) {
            throw CouponSoldOutException(couponCampaignId)
        }
    }

    private fun incrementIssuedQuantity(campaign: CouponCampaign) {
        campaign.issuedQuantity += 1
        couponCampaignRepository.save(campaign)
    }

    private fun saveIssue(couponCampaignId: Long, userId: Long): CouponIssue {
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
