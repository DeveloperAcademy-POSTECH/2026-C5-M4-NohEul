package com.coffee_coupon_api.repository

import com.coffee_coupon_api.domain.CouponCampaign
import com.coffee_coupon_api.domain.CouponTemplate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager
import org.springframework.orm.ObjectOptimisticLockingFailureException

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CouponCampaignRepositoryTest {

    @Autowired
    lateinit var couponCampaignRepository: CouponCampaignRepository

    @Autowired
    lateinit var couponTemplateRepository: CouponTemplateRepository

    @Autowired
    lateinit var testEntityManager: TestEntityManager

    @Test
    fun `쿠폰 캠페인을 저장하고 조회할 수 있다`() {
        val template = couponTemplateRepository.save(CouponTemplate(name = "아메리카노", discountRate = 10))

        val saved = couponCampaignRepository.save(
            CouponCampaign(couponTemplateId = template.id!!, totalQuantity = 10, issuedQuantity = 0),
        )

        val found = couponCampaignRepository.findById(saved.id!!).orElseThrow()

        assertEquals(template.id, found.couponTemplateId)
        assertEquals(10, found.totalQuantity)
        assertEquals(0, found.issuedQuantity)
    }

    @Test
    fun `서로 다른 세션이 같은 행을 읽고 나중에 저장을 시도하면 낙관적 락 예외가 발생한다`() {
        val template = couponTemplateRepository.save(CouponTemplate(name = "아메리카노", discountRate = 10))
        val saved = couponCampaignRepository.save(
            CouponCampaign(couponTemplateId = template.id!!, totalQuantity = 10, issuedQuantity = 0),
        )
        testEntityManager.flush()
        testEntityManager.clear()

        val first = couponCampaignRepository.findById(saved.id!!).orElseThrow()
        testEntityManager.clear()
        val second = couponCampaignRepository.findById(saved.id!!).orElseThrow()
        testEntityManager.clear()

        first.issuedQuantity += 1
        couponCampaignRepository.saveAndFlush(first)

        //  second는 여전히 version=0을 가지고 있고, DB의 version은 1로 증가했다.
        // 두 번째 업데이트 시도 시 버전 불일치가 감지되어야 한다.
        second.issuedQuantity += 1
        assertThrows(ObjectOptimisticLockingFailureException::class.java) {
            couponCampaignRepository.saveAndFlush(second)
        }
    }
}
