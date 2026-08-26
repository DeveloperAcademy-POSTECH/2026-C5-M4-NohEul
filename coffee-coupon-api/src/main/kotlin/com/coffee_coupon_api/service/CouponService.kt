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
    private val clock: Clock = Clock.systemDefaultZone(),
    private val couponIssueAttempter: CouponIssueAttempter,
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

        return couponIssueAttempter.completeIssue(campaign, couponCampaignId, userId)
    }

    @Transactional
    fun issueNoLock(couponCampaignId: Long, userId: Long): CouponIssue {
        // findById는 행을 잠그지 않아서, 재고 체크~증가 사이에 다른 트랜잭션이 끼어들 수 있다(Lost Update).
        // completeIssue를 그대로 쓰지 않는 이유: 그 안의 incrementIssuedQuantity는 엔티티 기반 save라
        // CouponCampaign의 @Version이 자동으로 버전 체크를 걸어버려서, "락 없음"이라는 전제가 깨진다.
        // 그래서 재고 증가만 @Version을 우회하는 벌크 UPDATE(incrementIssuedQuantityRaw)로 따로 한다.
        val campaign = couponCampaignRepository.findById(couponCampaignId)
            .orElseThrow { CouponNotFoundException(couponCampaignId) }

        if (LocalDateTime.now(clock).isBefore(campaign.openAt)) {
            throw CouponNotYetOpenException(couponCampaignId, campaign.openAt)
        }

        couponIssueAttempter.ensureNotAlreadyIssued(couponCampaignId, userId)
        couponIssueAttempter.ensureStockAvailable(campaign, couponCampaignId)
        incrementIssuedQuantityRaw(couponCampaignId)
        return couponIssueAttempter.saveIssue(couponCampaignId, userId)
    }

    // @Transactional을 붙이지 않는다: 이 메서드는 재시도 루프와 예외 처리만 할 뿐 DB 작업이
    // 전혀 없다. 여기 트랜잭션을 걸면 attemptIssue()의 REQUIRES_NEW가 매 시도마다 별도
    // 커넥션을 추가로 요구하게 되어, 동시 요청이 (풀 크기 / 2)를 넘는 순간 각 요청이 자기 바깥
    // 트랜잭션의 커넥션을 쥔 채 서로의 두 번째 커넥션을 기다리는 커넥션 풀 데드락이 발생한다.
    fun issueOptimistic(couponCampaignId: Long, userId: Long): CouponIssue {
        var lastException: ObjectOptimisticLockingFailureException? = null
        for (attempt in 1..optimisticMaxAttempts) {
            try {
                return couponIssueAttempter.attemptIssue(couponCampaignId, userId)
            } catch (e: ObjectOptimisticLockingFailureException) {
                lastException = e
                if (attempt < optimisticMaxAttempts) Thread.sleep(OPTIMISTIC_RETRY_DELAY_MS)
            }
        }
        throw CouponIssueConflictException(couponCampaignId, lastException)
    }

    private fun incrementIssuedQuantityRaw(couponCampaignId: Long) {
        couponCampaignRepository.incrementIssuedQuantityRaw(couponCampaignId)
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
