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
import java.time.ZoneId
import java.util.Optional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.any
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.orm.ObjectOptimisticLockingFailureException

class CouponServiceTest {

    private lateinit var couponCampaignRepository: CouponCampaignRepository
    private lateinit var couponTemplateRepository: CouponTemplateRepository
    private lateinit var couponIssueRepository: CouponIssueRepository
    private lateinit var couponIssueAttempter: CouponIssueAttempter
    private lateinit var couponService: CouponService

    @BeforeEach
    fun setUp() {
        couponCampaignRepository = mock(CouponCampaignRepository::class.java)
        couponTemplateRepository = mock(CouponTemplateRepository::class.java)
        couponIssueRepository = mock(CouponIssueRepository::class.java)
        // CouponIssueAttempter는 목이 아니라 실제 인스턴스를 씀 — completeIssue/ensureXxx 로직이
        // 이제 이 클래스 안에 있어서, 실제로 돌아야 기존 테스트들의 리포지토리 스텁이 그대로 유효함.
        couponIssueAttempter = CouponIssueAttempter(couponCampaignRepository, couponIssueRepository)
        couponService = CouponService(couponCampaignRepository, couponTemplateRepository, couponIssueAttempter = couponIssueAttempter)
    }

    @Test
    fun `issuePessimistic - 발급 가능한 쿠폰은 정상적으로 발급된다`() {
        val campaign = CouponCampaign(couponTemplateId = 1L, totalQuantity = 10, issuedQuantity = 0)
        `when`(couponCampaignRepository.findOpenAtById(1L)).thenReturn(campaign.openAt)
        `when`(couponCampaignRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(campaign))
        `when`(couponIssueRepository.existsByCouponCampaignIdAndUserId(1L, 100L)).thenReturn(false)
        `when`(couponCampaignRepository.save(campaign)).thenReturn(campaign)
        val savedIssue = CouponIssue(couponCampaignId = 1L, userId = 100L)
        `when`(couponIssueRepository.save(any(CouponIssue::class.java))).thenReturn(savedIssue)

        val result = couponService.issuePessimistic(1L, 100L)

        assertEquals(1, campaign.issuedQuantity)
        assertEquals(100L, result.userId)
    }

    @Test
    fun `issuePessimistic - 존재하지 않는 쿠폰이면 CouponNotFoundException이 발생한다`() {
        `when`(couponCampaignRepository.findOpenAtById(999L)).thenReturn(null)

        assertThrows(CouponNotFoundException::class.java) {
            couponService.issuePessimistic(999L, 100L)
        }
    }

    @Test
    fun `issuePessimistic - 오픈 시각 조회 이후 캠페인이 삭제되면 CouponNotFoundException이 발생한다`() {
        `when`(couponCampaignRepository.findOpenAtById(1L)).thenReturn(LocalDateTime.now().minusMinutes(1))
        `when`(couponCampaignRepository.findByIdForUpdate(1L)).thenReturn(Optional.empty())

        assertThrows(CouponNotFoundException::class.java) {
            couponService.issuePessimistic(1L, 100L)
        }
    }

    @Test
    fun `issuePessimistic - 오픈 시각 이전에 발급 요청하면 CouponNotYetOpenException이 발생한다`() {
        val openAt = LocalDateTime.of(2026, 8, 14, 10, 0)
        val threeSecondsBeforeOpen = openAt.minusSeconds(3).atZone(ZoneId.systemDefault()).toInstant()
        val fixedClock = Clock.fixed(threeSecondsBeforeOpen, ZoneId.systemDefault())
        val serviceWithFixedClock =
            CouponService(couponCampaignRepository, couponTemplateRepository, fixedClock, couponIssueAttempter)
        `when`(couponCampaignRepository.findOpenAtById(1L)).thenReturn(openAt)

        assertThrows(CouponNotYetOpenException::class.java) {
            serviceWithFixedClock.issuePessimistic(1L, 100L)
        }
    }

    @Test
    fun `issuePessimistic - 오픈 시각 정각에 발급 요청하면 정상적으로 발급된다`() {
        val openAt = LocalDateTime.of(2026, 8, 14, 10, 0)
        val exactlyOpenInstant = openAt.atZone(ZoneId.systemDefault()).toInstant()
        val fixedClock = Clock.fixed(exactlyOpenInstant, ZoneId.systemDefault())
        val serviceWithFixedClock =
            CouponService(couponCampaignRepository, couponTemplateRepository, fixedClock, couponIssueAttempter)
        val campaign = CouponCampaign(couponTemplateId = 1L, totalQuantity = 10, issuedQuantity = 0, openAt = openAt)
        `when`(couponCampaignRepository.findOpenAtById(1L)).thenReturn(openAt)
        `when`(couponCampaignRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(campaign))
        `when`(couponIssueRepository.existsByCouponCampaignIdAndUserId(1L, 100L)).thenReturn(false)
        `when`(couponCampaignRepository.save(campaign)).thenReturn(campaign)
        val savedIssue = CouponIssue(couponCampaignId = 1L, userId = 100L)
        `when`(couponIssueRepository.save(any(CouponIssue::class.java))).thenReturn(savedIssue)

        val result = serviceWithFixedClock.issuePessimistic(1L, 100L)

        assertEquals(1, campaign.issuedQuantity)
        assertEquals(100L, result.userId)
    }

    @Test
    fun `issuePessimistic - 이미 발급받은 사용자는 DuplicateIssueException이 발생한다`() {
        val campaign = CouponCampaign(couponTemplateId = 1L, totalQuantity = 10, issuedQuantity = 1)
        `when`(couponCampaignRepository.findOpenAtById(1L)).thenReturn(campaign.openAt)
        `when`(couponCampaignRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(campaign))
        `when`(couponIssueRepository.existsByCouponCampaignIdAndUserId(1L, 100L)).thenReturn(true)

        assertThrows(DuplicateIssueException::class.java) {
            couponService.issuePessimistic(1L, 100L)
        }
    }

    @Test
    fun `issuePessimistic - 재고가 소진되면 CouponSoldOutException이 발생한다`() {
        val campaign = CouponCampaign(couponTemplateId = 1L, totalQuantity = 1, issuedQuantity = 1)
        `when`(couponCampaignRepository.findOpenAtById(1L)).thenReturn(campaign.openAt)
        `when`(couponCampaignRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(campaign))
        `when`(couponIssueRepository.existsByCouponCampaignIdAndUserId(1L, 100L)).thenReturn(false)

        assertThrows(CouponSoldOutException::class.java) {
            couponService.issuePessimistic(1L, 100L)
        }
    }

    @Test
    fun `issueNoLock - 발급 가능한 쿠폰은 정상적으로 발급된다`() {
        val campaign = CouponCampaign(couponTemplateId = 1L, totalQuantity = 10, issuedQuantity = 0)
        `when`(couponCampaignRepository.findById(1L)).thenReturn(Optional.of(campaign))
        `when`(couponIssueRepository.existsByCouponCampaignIdAndUserId(1L, 100L)).thenReturn(false)
        val savedIssue = CouponIssue(couponCampaignId = 1L, userId = 100L)
        `when`(couponIssueRepository.save(any(CouponIssue::class.java))).thenReturn(savedIssue)

        val result = couponService.issueNoLock(1L, 100L)

        verify(couponCampaignRepository).incrementIssuedQuantityRaw(1L)
        assertEquals(100L, result.userId)
    }

    @Test
    fun `issueNoLock - 존재하지 않는 쿠폰이면 CouponNotFoundException이 발생한다`() {
        `when`(couponCampaignRepository.findById(999L)).thenReturn(Optional.empty())

        assertThrows(CouponNotFoundException::class.java) {
            couponService.issueNoLock(999L, 100L)
        }
    }

    @Test
    fun `issueNoLock - 오픈 시각 이전에 발급 요청하면 CouponNotYetOpenException이 발생한다`() {
        val openAt = LocalDateTime.of(2026, 8, 14, 10, 0)
        val threeSecondsBeforeOpen = openAt.minusSeconds(3).atZone(ZoneId.systemDefault()).toInstant()
        val fixedClock = Clock.fixed(threeSecondsBeforeOpen, ZoneId.systemDefault())
        val serviceWithFixedClock =
            CouponService(couponCampaignRepository, couponTemplateRepository, fixedClock, couponIssueAttempter)
        val campaign = CouponCampaign(couponTemplateId = 1L, totalQuantity = 10, issuedQuantity = 0, openAt = openAt)
        `when`(couponCampaignRepository.findById(1L)).thenReturn(Optional.of(campaign))

        assertThrows(CouponNotYetOpenException::class.java) {
            serviceWithFixedClock.issueNoLock(1L, 100L)
        }
    }

    @Test
    fun `issueNoLock - 이미 발급받은 사용자는 DuplicateIssueException이 발생한다`() {
        val campaign = CouponCampaign(couponTemplateId = 1L, totalQuantity = 10, issuedQuantity = 1)
        `when`(couponCampaignRepository.findById(1L)).thenReturn(Optional.of(campaign))
        `when`(couponIssueRepository.existsByCouponCampaignIdAndUserId(1L, 100L)).thenReturn(true)

        assertThrows(DuplicateIssueException::class.java) {
            couponService.issueNoLock(1L, 100L)
        }
    }

    @Test
    fun `issueNoLock - 재고가 소진되면 CouponSoldOutException이 발생한다`() {
        val campaign = CouponCampaign(couponTemplateId = 1L, totalQuantity = 1, issuedQuantity = 1)
        `when`(couponCampaignRepository.findById(1L)).thenReturn(Optional.of(campaign))
        `when`(couponIssueRepository.existsByCouponCampaignIdAndUserId(1L, 100L)).thenReturn(false)

        assertThrows(CouponSoldOutException::class.java) {
            couponService.issueNoLock(1L, 100L)
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

    @Test
    fun `issueOptimistic - 발급 가능한 쿠폰은 정상적으로 발급된다`() {
        val campaign = CouponCampaign(couponTemplateId = 1L, totalQuantity = 10, issuedQuantity = 0)
        `when`(couponCampaignRepository.findById(1L)).thenReturn(Optional.of(campaign))
        `when`(couponIssueRepository.existsByCouponCampaignIdAndUserId(1L, 100L)).thenReturn(false)
        `when`(couponCampaignRepository.save(campaign)).thenReturn(campaign)
        val savedIssue = CouponIssue(couponCampaignId = 1L, userId = 100L)
        `when`(couponIssueRepository.save(any(CouponIssue::class.java))).thenReturn(savedIssue)

        val result = couponService.issueOptimistic(1L, 100L)

        assertEquals(1, campaign.issuedQuantity)
        assertEquals(100L, result.userId)
    }

    @Test
    fun `issueOptimistic - 존재하지 않는 쿠폰이면 CouponNotFoundException이 발생한다`() {
        `when`(couponCampaignRepository.findById(999L)).thenReturn(Optional.empty())

        assertThrows(CouponNotFoundException::class.java) {
            couponService.issueOptimistic(999L, 100L)
        }
    }

    @Test
    fun `issueOptimistic - 오픈 시각 이전에 발급 요청하면 CouponNotYetOpenException이 발생한다`() {
        val openAt = LocalDateTime.of(2026, 8, 14, 10, 0)
        val threeSecondsBeforeOpen = openAt.minusSeconds(3).atZone(ZoneId.systemDefault()).toInstant()
        val fixedClock = Clock.fixed(threeSecondsBeforeOpen, ZoneId.systemDefault())
        // issueOptimistic의 openAt 체크는 이제 attemptIssue 안(CouponIssueAttempter)에서 일어나므로,
        // CouponService가 아니라 CouponIssueAttempter 쪽에 fixedClock을 넣어야 한다.
        val attempterWithFixedClock = CouponIssueAttempter(couponCampaignRepository, couponIssueRepository, fixedClock)
        val serviceWithFixedClock =
            CouponService(couponCampaignRepository, couponTemplateRepository, couponIssueAttempter = attempterWithFixedClock)
        val campaign = CouponCampaign(couponTemplateId = 1L, totalQuantity = 10, issuedQuantity = 0, openAt = openAt)
        `when`(couponCampaignRepository.findById(1L)).thenReturn(Optional.of(campaign))

        assertThrows(CouponNotYetOpenException::class.java) {
            serviceWithFixedClock.issueOptimistic(1L, 100L)
        }
    }

    @Test
    fun `issueOptimistic - 이미 발급받은 사용자는 DuplicateIssueException이 발생한다`() {
        val campaign = CouponCampaign(couponTemplateId = 1L, totalQuantity = 10, issuedQuantity = 1)
        `when`(couponCampaignRepository.findById(1L)).thenReturn(Optional.of(campaign))
        `when`(couponIssueRepository.existsByCouponCampaignIdAndUserId(1L, 100L)).thenReturn(true)

        assertThrows(DuplicateIssueException::class.java) {
            couponService.issueOptimistic(1L, 100L)
        }
    }

    @Test
    fun `issueOptimistic - 재고가 소진되면 CouponSoldOutException이 발생한다`() {
        val campaign = CouponCampaign(couponTemplateId = 1L, totalQuantity = 1, issuedQuantity = 1)
        `when`(couponCampaignRepository.findById(1L)).thenReturn(Optional.of(campaign))
        `when`(couponIssueRepository.existsByCouponCampaignIdAndUserId(1L, 100L)).thenReturn(false)

        assertThrows(CouponSoldOutException::class.java) {
            couponService.issueOptimistic(1L, 100L)
        }
    }

    @Test
    fun `issueOptimistic - 재시도를 다 써도 계속 충돌하면 CouponIssueConflictException이 발생한다`() {
        val campaign = CouponCampaign(couponTemplateId = 1L, totalQuantity = 10, issuedQuantity = 0)
        val serviceWithOneAttempt = CouponService(
            couponCampaignRepository,
            couponTemplateRepository,
            couponIssueAttempter = couponIssueAttempter,
            optimisticMaxAttempts = 1,
        )
        `when`(couponCampaignRepository.findById(1L)).thenReturn(Optional.of(campaign))
        `when`(couponIssueRepository.existsByCouponCampaignIdAndUserId(1L, 100L)).thenReturn(false)
        `when`(couponCampaignRepository.save(campaign)).thenReturn(campaign)
        val savedIssue = CouponIssue(couponCampaignId = 1L, userId = 100L)
        `when`(couponIssueRepository.save(any(CouponIssue::class.java))).thenReturn(savedIssue)
        doThrow(ObjectOptimisticLockingFailureException(CouponCampaign::class.java, 1L))
            .`when`(couponCampaignRepository).save(campaign)

        assertThrows(CouponIssueConflictException::class.java) {
            serviceWithOneAttempt.issueOptimistic(1L, 100L)
        }
    }
}
