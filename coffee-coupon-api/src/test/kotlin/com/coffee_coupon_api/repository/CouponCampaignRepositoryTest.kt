package com.coffee_coupon_api.repository

import com.coffee_coupon_api.domain.CouponCampaign
import com.coffee_coupon_api.domain.CouponTemplate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CouponCampaignRepositoryTest {

    @Autowired
    lateinit var couponCampaignRepository: CouponCampaignRepository

    @Autowired
    lateinit var couponTemplateRepository: CouponTemplateRepository

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
}
