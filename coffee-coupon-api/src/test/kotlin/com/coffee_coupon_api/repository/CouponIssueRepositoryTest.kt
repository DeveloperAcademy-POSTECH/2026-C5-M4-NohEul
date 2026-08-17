package com.coffee_coupon_api.repository

import com.coffee_coupon_api.domain.CouponIssue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.dao.DataIntegrityViolationException

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CouponIssueRepositoryTest {

    @Autowired
    lateinit var couponIssueRepository: CouponIssueRepository

    @Test
    fun `발급 이력을 저장하고 조회할 수 있다`() {
        couponIssueRepository.save(CouponIssue(couponCampaignId = 1L, userId = 100L))

        val exists = couponIssueRepository.existsByCouponCampaignIdAndUserId(1L, 100L)

        assertEquals(true, exists)
    }

    @Test
    fun `같은 쿠폰에 같은 사용자를 중복 저장하면 예외가 발생한다`() {
        couponIssueRepository.saveAndFlush(CouponIssue(couponCampaignId = 1L, userId = 100L))

        assertThrows(DataIntegrityViolationException::class.java) {
            couponIssueRepository.saveAndFlush(CouponIssue(couponCampaignId = 1L, userId = 100L))
        }
    }
}
