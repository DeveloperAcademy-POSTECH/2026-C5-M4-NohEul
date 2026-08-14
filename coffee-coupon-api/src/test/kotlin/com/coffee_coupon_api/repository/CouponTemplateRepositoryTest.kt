package com.coffee_coupon_api.repository

import com.coffee_coupon_api.domain.CouponTemplate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CouponTemplateRepositoryTest {

    @Autowired
    lateinit var couponTemplateRepository: CouponTemplateRepository

    @Test
    fun `쿠폰 템플릿을 저장하고 조회할 수 있다`() {
        val saved = couponTemplateRepository.save(CouponTemplate(name = "아메리카노 10% 할인", discountRate = 10))

        val found = couponTemplateRepository.findById(saved.id!!).orElseThrow()

        assertEquals("아메리카노 10% 할인", found.name)
        assertEquals(10, found.discountRate)
    }
}
