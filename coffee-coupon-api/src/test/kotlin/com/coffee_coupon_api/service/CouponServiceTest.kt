package com.coffee_coupon_api.service

import com.coffee_coupon_api.domain.CouponCampaign
import com.coffee_coupon_api.domain.CouponIssue
import com.coffee_coupon_api.domain.CouponTemplate
import com.coffee_coupon_api.exception.CouponNotFoundException
import com.coffee_coupon_api.exception.CouponSoldOutException
import com.coffee_coupon_api.exception.DuplicateIssueException
import com.coffee_coupon_api.repository.CouponCampaignRepository
import com.coffee_coupon_api.repository.CouponIssueRepository
import com.coffee_coupon_api.repository.CouponTemplateRepository
import java.util.Optional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class CouponServiceTest {

    private lateinit var couponCampaignRepository: CouponCampaignRepository
    private lateinit var couponTemplateRepository: CouponTemplateRepository
    private lateinit var couponIssueRepository: CouponIssueRepository
    private lateinit var couponService: CouponService

    @BeforeEach
    fun setUp() {
        couponCampaignRepository = mock(CouponCampaignRepository::class.java)
        couponTemplateRepository = mock(CouponTemplateRepository::class.java)
        couponIssueRepository = mock(CouponIssueRepository::class.java)
        couponService = CouponService(couponCampaignRepository, couponTemplateRepository, couponIssueRepository)
    }

    @Test
    fun `발급 가능한 쿠폰은 정상적으로 발급된다`() {
        val campaign = CouponCampaign(couponTemplateId = 1L, totalQuantity = 10, issuedQuantity = 0)
        `when`(couponCampaignRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(campaign))
        `when`(couponIssueRepository.existsByCouponCampaignIdAndUserId(1L, 100L)).thenReturn(false)
        `when`(couponCampaignRepository.save(campaign)).thenReturn(campaign)
        val savedIssue = CouponIssue(couponCampaignId = 1L, userId = 100L)
        `when`(couponIssueRepository.save(any(CouponIssue::class.java))).thenReturn(savedIssue)

        val result = couponService.issue(1L, 100L)

        assertEquals(1, campaign.issuedQuantity)
        assertEquals(100L, result.userId)
    }

    @Test
    fun `존재하지 않는 쿠폰이면 CouponNotFoundException이 발생한다`() {
        `when`(couponCampaignRepository.findByIdForUpdate(999L)).thenReturn(Optional.empty())

        assertThrows(CouponNotFoundException::class.java) {
            couponService.issue(999L, 100L)
        }
    }

    @Test
    fun `이미 발급받은 사용자는 DuplicateIssueException이 발생한다`() {
        val campaign = CouponCampaign(couponTemplateId = 1L, totalQuantity = 10, issuedQuantity = 1)
        `when`(couponCampaignRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(campaign))
        `when`(couponIssueRepository.existsByCouponCampaignIdAndUserId(1L, 100L)).thenReturn(true)

        assertThrows(DuplicateIssueException::class.java) {
            couponService.issue(1L, 100L)
        }
    }

    @Test
    fun `재고가 소진되면 CouponSoldOutException이 발생한다`() {
        val campaign = CouponCampaign(couponTemplateId = 1L, totalQuantity = 1, issuedQuantity = 1)
        `when`(couponCampaignRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(campaign))
        `when`(couponIssueRepository.existsByCouponCampaignIdAndUserId(1L, 100L)).thenReturn(false)

        assertThrows(CouponSoldOutException::class.java) {
            couponService.issue(1L, 100L)
        }
    }

    @Test
    fun `존재하는 쿠폰을 조회하면 쿠폰 정보를 반환한다`() {
        val campaign = CouponCampaign(couponTemplateId = 1L, totalQuantity = 10, issuedQuantity = 3)
        val template = CouponTemplate(name = "아메리카노", discountRate = 10)
        `when`(couponCampaignRepository.findById(1L)).thenReturn(Optional.of(campaign))
        `when`(couponTemplateRepository.findById(1L)).thenReturn(Optional.of(template))

        val (resultCampaign, resultTemplate) = couponService.getCoupon(1L)

        assertEquals("아메리카노", resultTemplate.name)
        assertEquals(10, resultCampaign.totalQuantity)
    }

    @Test
    fun `존재하지 않는 쿠폰을 조회하면 CouponNotFoundException이 발생한다`() {
        `when`(couponCampaignRepository.findById(999L)).thenReturn(Optional.empty())

        assertThrows(CouponNotFoundException::class.java) {
            couponService.getCoupon(999L)
        }
    }
}
