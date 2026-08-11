package com.coffee_coupon_api.controller

import com.coffee_coupon_api.domain.Coupon
import com.coffee_coupon_api.domain.CouponIssue
import com.coffee_coupon_api.repository.CouponIssueRepository
import com.coffee_coupon_api.repository.CouponRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CouponControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var couponRepository: CouponRepository

    @Autowired
    lateinit var couponIssueRepository: CouponIssueRepository

    private lateinit var coupon: Coupon

    @BeforeEach
    fun setUp() {
        coupon = couponRepository.save(Coupon(name = "아메리카노", totalQuantity = 1, issuedQuantity = 0))
    }

    @Test
    fun `쿠폰을 정상적으로 발급받는다`() {
        mockMvc.perform(
            post("/api/coupons/${coupon.id}/issue")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("userId" to 1L))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.userId").value(1))
    }

    @Test
    fun `존재하지 않는 쿠폰은 404를 반환한다`() {
        mockMvc.perform(
            post("/api/coupons/99999/issue")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("userId" to 1L))),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("COUPON_NOT_FOUND"))
    }

    @Test
    fun `이미 발급받은 사용자는 409를 반환한다`() {
        couponIssueRepository.save(CouponIssue(couponId = coupon.id!!, userId = 1L))

        mockMvc.perform(
            post("/api/coupons/${coupon.id}/issue")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("userId" to 1L))),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("DUPLICATE_ISSUE"))
    }

    @Test
    fun `재고가 소진되면 409를 반환한다`() {
        couponIssueRepository.save(CouponIssue(couponId = coupon.id!!, userId = 1L))
        coupon.issuedQuantity = 1
        couponRepository.save(coupon)

        mockMvc.perform(
            post("/api/coupons/${coupon.id}/issue")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("userId" to 2L))),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("COUPON_SOLD_OUT"))
    }

    @Test
    fun `쿠폰 잔여 수량을 조회한다`() {
        mockMvc.perform(get("/api/coupons/${coupon.id}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.remainingQuantity").value(1))
    }

    @Test
    fun `존재하지 않는 쿠폰 조회는 404를 반환한다`() {
        mockMvc.perform(get("/api/coupons/99999"))
            .andExpect(status().isNotFound)
    }
}
