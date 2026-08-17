package com.coffee_coupon_api.service

import com.coffee_coupon_api.domain.CouponCampaign
import com.coffee_coupon_api.domain.CouponTemplate
import com.coffee_coupon_api.repository.CouponCampaignRepository
import com.coffee_coupon_api.repository.CouponTemplateRepository
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class CouponServiceConcurrencyTest {

    @Autowired
    lateinit var couponService: CouponService

    @Autowired
    lateinit var couponCampaignRepository: CouponCampaignRepository

    @Autowired
    lateinit var couponTemplateRepository: CouponTemplateRepository

    @Test
    fun `비관적 락을 걸면 재고 1개짜리 쿠폰에 N명이 동시에 요청해도 1명만 성공한다`() {
        val template = couponTemplateRepository.save(CouponTemplate(name = "아메리카노", discountRate = 10))
        val campaign = couponCampaignRepository.save(
            CouponCampaign(
                couponTemplateId = template.id!!,
                totalQuantity = 1,
                issuedQuantity = 0,
                openAt = LocalDateTime.now().minusMinutes(1),
            ),
        )
        val threadCount = 30
        val executor = Executors.newFixedThreadPool(threadCount)
        val startGate = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val successCount = AtomicInteger(0)
        val failCount = AtomicInteger(0)

        repeat(threadCount) { i ->
            executor.submit {
                startGate.await()
                try {
                    couponService.issue(campaign.id!!, userId = i.toLong())
                    successCount.incrementAndGet()
                } catch (e: Exception) {
                    failCount.incrementAndGet()
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        startGate.countDown()
        doneLatch.await(10, TimeUnit.SECONDS)
        executor.shutdown()

        val finalCampaign = couponCampaignRepository.findById(campaign.id!!).orElseThrow()
        println(
            "[재현 결과] totalQuantity=1, 동시 요청=$threadCount, " +
                "성공=${successCount.get()}, 실패=${failCount.get()}, 최종 issuedQuantity=${finalCampaign.issuedQuantity}",
        )

        assertEquals(1, successCount.get())
        assertEquals(threadCount - 1, failCount.get())
        assertEquals(1, finalCampaign.issuedQuantity)
    }
}
