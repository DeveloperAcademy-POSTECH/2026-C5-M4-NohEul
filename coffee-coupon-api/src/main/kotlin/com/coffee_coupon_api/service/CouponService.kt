package com.coffee_coupon_api.service

import com.coffee_coupon_api.domain.CouponCampaign
import com.coffee_coupon_api.domain.CouponIssue
import com.coffee_coupon_api.domain.CouponTemplate
import com.coffee_coupon_api.exception.CouponIssueConflictException
import com.coffee_coupon_api.exception.CouponNotFoundException
import com.coffee_coupon_api.exception.CouponNotYetOpenException
import com.coffee_coupon_api.exception.CouponSoldOutException
import com.coffee_coupon_api.exception.DuplicateIssueException
import com.coffee_coupon_api.repository.CouponCampaignRepository
import com.coffee_coupon_api.repository.CouponIssueRepository
import com.coffee_coupon_api.repository.CouponTemplateRepository
import java.time.Clock
import java.time.LocalDateTime
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

private const val OPTIMISTIC_RETRY_DELAY_MS = 20L

@Service
class CouponService(
    private val couponCampaignRepository: CouponCampaignRepository,
    private val couponTemplateRepository: CouponTemplateRepository,
    private val couponIssueRepository: CouponIssueRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val optimisticMaxAttempts: Int = 3,
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

    @Transactional
    fun issueOptimistic(couponCampaignId: Long, userId: Long): CouponIssue {
        var lastException: ObjectOptimisticLockingFailureException? = null
        for (attempt in 1..optimisticMaxAttempts) {
            try {
                val campaign = couponCampaignRepository.findById(couponCampaignId)
                    .orElseThrow { CouponNotFoundException(couponCampaignId) }
                if (LocalDateTime.now(clock).isBefore(campaign.openAt)) {
                    throw CouponNotYetOpenException(couponCampaignId, campaign.openAt)
                }
                val result = completeIssue(campaign, couponCampaignId, userId)
                couponCampaignRepository.flush()
                return result
            } catch (e: ObjectOptimisticLockingFailureException) {
                lastException = e
                if (attempt < optimisticMaxAttempts) Thread.sleep(OPTIMISTIC_RETRY_DELAY_MS)
            }
        }
        throw CouponIssueConflictException(couponCampaignId, lastException)
    }

    private fun completeIssue(campaign: CouponCampaign, couponCampaignId: Long, userId: Long): CouponIssue {
        ensureNotAlreadyIssued(couponCampaignId, userId)
        ensureStockAvailable(campaign, couponCampaignId)
        incrementIssuedQuantity(campaign)
        couponCampaignRepository.flush()
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
