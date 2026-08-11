package com.coffee_coupon_api.repository

import com.coffee_coupon_api.domain.Coupon
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CouponRepositoryTest {

    @Autowired
    lateinit var couponRepository: CouponRepository

    @Test
    fun `쿠폰을 저장하고 조회할 수 있다`() {
        val saved = couponRepository.save(Coupon(name = "아메리카노", totalQuantity = 10, issuedQuantity = 0))

        val found = couponRepository.findById(saved.id!!).orElseThrow()

        assertEquals("아메리카노", found.name)
        assertEquals(10, found.totalQuantity)
        assertEquals(0, found.issuedQuantity)
    }
}
