package com.coffee_coupon_api.service

import com.coffee_coupon_api.domain.CouponIssue
import com.coffee_coupon_api.exception.CouponNotFoundException
import com.coffee_coupon_api.exception.CouponNotYetOpenException
import com.coffee_coupon_api.exception.CouponSoldOutException
import com.coffee_coupon_api.exception.DuplicateIssueException
import com.coffee_coupon_api.repository.CouponCampaignRepository
import com.coffee_coupon_api.repository.CouponIssueRepository
import java.time.Clock
import java.time.LocalDateTime
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

// SynchronizedCouponIssuer가 락을 쥔 채로 호출하는 안쪽 빈.
// 커밋은 이 빈의 프록시가 메서드 반환 후에 하므로, 바깥의 락은 커밋이 끝난 뒤에 풀린다.
// 공유 헬퍼(CouponIssueAttempter)를 쓰지 않는 이유: 한 전략의 변경이 다른 전략 결과를 바꾸지 않게 하려고.
@Service
class SynchronizedCouponIssueProcessor(
    private val couponCampaignRepository: CouponCampaignRepository,
    private val couponIssueRepository: CouponIssueRepository,
    private val clock: Clock,
) {

    @Transactional
    fun issue(couponCampaignId: Long, userId: Long): CouponIssue {
        val campaign = couponCampaignRepository.findById(couponCampaignId)
            .orElseThrow { CouponNotFoundException(couponCampaignId) }

        if (LocalDateTime.now(clock).isBefore(campaign.openAt)) {
            throw CouponNotYetOpenException(couponCampaignId, campaign.openAt)
        }
        if (couponIssueRepository.existsByCouponCampaignIdAndUserId(couponCampaignId, userId)) {
            throw DuplicateIssueException(couponCampaignId, userId)
        }
        if (campaign.issuedQuantity >= campaign.totalQuantity) {
            throw CouponSoldOutException(couponCampaignId)
        }

        // @Version을 우회하는 벌크 UPDATE. save()로 올리면 낙관적 락 체크가 함께 걸려서
        // 과발급을 막은 게 synchronized 덕분인지 흐려진다.
        couponCampaignRepository.incrementIssuedQuantityRaw(couponCampaignId)
        return couponIssueRepository.save(CouponIssue(couponCampaignId = couponCampaignId, userId = userId))
    }
}
