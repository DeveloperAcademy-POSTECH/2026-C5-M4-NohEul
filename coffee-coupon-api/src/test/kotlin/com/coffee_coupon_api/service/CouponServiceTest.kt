package com.coffee_coupon_api.service

import com.coffee_coupon_api.domain.Coupon
import com.coffee_coupon_api.domain.CouponIssue
import com.coffee_coupon_api.exception.CouponNotFoundException
import com.coffee_coupon_api.exception.CouponSoldOutException
import com.coffee_coupon_api.exception.DuplicateIssueException
import com.coffee_coupon_api.repository.CouponIssueRepository
import com.coffee_coupon_api.repository.CouponRepository
import java.util.Optional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class CouponServiceTest {

    private lateinit var couponRepository: CouponRepository
    private lateinit var couponIssueRepository: CouponIssueRepository
    private lateinit var couponService: CouponService

    @BeforeEach
    fun setUp() {
        couponRepository = mock(CouponRepository::class.java)
        couponIssueRepository = mock(CouponIssueRepository::class.java)
        couponService = CouponService(couponRepository, couponIssueRepository)
    }

    @Test
    fun `발급 가능한 쿠폰은 정상적으로 발급된다`() {
        val coupon = Coupon(name = "아메리카노", totalQuantity = 10, issuedQuantity = 0)
        `when`(couponRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(coupon))
        `when`(couponIssueRepository.existsByCouponIdAndUserId(1L, 100L)).thenReturn(false)
        `when`(couponRepository.save(coupon)).thenReturn(coupon)
        val savedIssue = CouponIssue(couponId = 1L, userId = 100L)
        `when`(couponIssueRepository.save(any(CouponIssue::class.java))).thenReturn(savedIssue)

        val result = couponService.issue(1L, 100L)

        assertEquals(1, coupon.issuedQuantity)
        assertEquals(100L, result.userId)
    }

    @Test
    fun `존재하지 않는 쿠폰이면 CouponNotFoundException이 발생한다`() {
        `when`(couponRepository.findByIdForUpdate(999L)).thenReturn(Optional.empty())

        assertThrows(CouponNotFoundException::class.java) {
            couponService.issue(999L, 100L)
        }
    }

    @Test
    fun `이미 발급받은 사용자는 DuplicateIssueException이 발생한다`() {
        val coupon = Coupon(name = "아메리카노", totalQuantity = 10, issuedQuantity = 1)
        `when`(couponRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(coupon))
        `when`(couponIssueRepository.existsByCouponIdAndUserId(1L, 100L)).thenReturn(true)

        assertThrows(DuplicateIssueException::class.java) {
            couponService.issue(1L, 100L)
        }
    }

    @Test
    fun `재고가 소진되면 CouponSoldOutException이 발생한다`() {
        val coupon = Coupon(name = "아메리카노", totalQuantity = 1, issuedQuantity = 1)
        `when`(couponRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(coupon))
        `when`(couponIssueRepository.existsByCouponIdAndUserId(1L, 100L)).thenReturn(false)

        assertThrows(CouponSoldOutException::class.java) {
            couponService.issue(1L, 100L)
        }
    }

    @Test
    fun `존재하는 쿠폰을 조회하면 쿠폰 정보를 반환한다`() {
        val coupon = Coupon(name = "아메리카노", totalQuantity = 10, issuedQuantity = 3)
        `when`(couponRepository.findById(1L)).thenReturn(Optional.of(coupon))

        val result = couponService.getCoupon(1L)

        assertEquals("아메리카노", result.name)
    }

    @Test
    fun `존재하지 않는 쿠폰을 조회하면 CouponNotFoundException이 발생한다`() {
        `when`(couponRepository.findById(999L)).thenReturn(Optional.empty())

        assertThrows(CouponNotFoundException::class.java) {
            couponService.getCoupon(999L)
        }
    }
}
