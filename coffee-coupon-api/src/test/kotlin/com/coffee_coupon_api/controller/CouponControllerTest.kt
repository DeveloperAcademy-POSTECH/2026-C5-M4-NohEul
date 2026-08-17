package com.coffee_coupon_api.controller

import com.coffee_coupon_api.domain.CouponCampaign
import com.coffee_coupon_api.domain.CouponIssue
import com.coffee_coupon_api.domain.CouponTemplate
import com.coffee_coupon_api.repository.CouponCampaignRepository
import com.coffee_coupon_api.repository.CouponIssueRepository
import com.coffee_coupon_api.repository.CouponTemplateRepository
import java.time.LocalDateTime
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
    lateinit var couponCampaignRepository: CouponCampaignRepository

    @Autowired
    lateinit var couponTemplateRepository: CouponTemplateRepository

    @Autowired
    lateinit var couponIssueRepository: CouponIssueRepository

    private lateinit var campaign: CouponCampaign

    @BeforeEach
    fun setUp() {
        val template = couponTemplateRepository.save(CouponTemplate(name = "아메리카노", discountRate = 10))
        campaign = couponCampaignRepository.save(
            CouponCampaign(couponTemplateId = template.id!!, totalQuantity = 1, issuedQuantity = 0),
        )
    }

    @Test
    fun `issue-pessimistic - 쿠폰을 정상적으로 발급받는다`() {
        mockMvc.perform(
            post("/api/coupons/${campaign.id}/issue-pessimistic")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("userId" to 1L))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.userId").value(1))
    }

    @Test
    fun `issue-no-lock - 쿠폰을 정상적으로 발급받는다`() {
        mockMvc.perform(
            post("/api/coupons/${campaign.id}/issue-no-lock")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("userId" to 1L))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.userId").value(1))
    }

    @Test
    fun `존재하지 않는 쿠폰은 404를 반환한다`() {
        mockMvc.perform(
            post("/api/coupons/99999/issue-pessimistic")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("userId" to 1L))),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("COUPON_NOT_FOUND"))
    }

    @Test
    fun `이미 발급받은 사용자는 409를 반환한다`() {
        couponIssueRepository.save(CouponIssue(couponCampaignId = campaign.id!!, userId = 1L))

        mockMvc.perform(
            post("/api/coupons/${campaign.id}/issue-pessimistic")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("userId" to 1L))),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("DUPLICATE_ISSUE"))
    }

    @Test
    fun `재고가 소진되면 409를 반환한다`() {
        couponIssueRepository.save(CouponIssue(couponCampaignId = campaign.id!!, userId = 1L))
        campaign.issuedQuantity = 1
        couponCampaignRepository.save(campaign)

        mockMvc.perform(
            post("/api/coupons/${campaign.id}/issue-pessimistic")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("userId" to 2L))),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("COUPON_SOLD_OUT"))
    }

    @Test
    fun `오픈 전 쿠폰 발급 요청은 403을 반환한다`() {
        val futureTemplate = couponTemplateRepository.save(CouponTemplate(name = "아이스티", discountRate = 20))
        val futureCampaign = couponCampaignRepository.save(
            CouponCampaign(
                couponTemplateId = futureTemplate.id!!,
                totalQuantity = 10,
                issuedQuantity = 0,
                openAt = LocalDateTime.now().plusHours(1),
            ),
        )

        mockMvc.perform(
            post("/api/coupons/${futureCampaign.id}/issue-pessimistic")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("userId" to 1L))),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("COUPON_NOT_YET_OPEN"))
    }

    @Test
    fun `쿠폰 잔여 수량을 조회한다`() {
        mockMvc.perform(get("/api/coupons/${campaign.id}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.remainingQuantity").value(1))
    }

    @Test
    fun `존재하지 않는 쿠폰 조회는 404를 반환한다`() {
        mockMvc.perform(get("/api/coupons/99999"))
            .andExpect(status().isNotFound)
    }
}
