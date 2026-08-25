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
class ScratchIssueOptimisticFixVerifyTest {

    @Autowired
    lateinit var couponService: CouponService

    @Autowired
    lateinit var couponCampaignRepository: CouponCampaignRepository

    @Autowired
    lateinit var couponTemplateRepository: CouponTemplateRepository

    @Test
    fun `REQUIRES_NEW 적용 후 재고 2개짜리에 스레드 2개 동시 요청하면 둘 다 성공하고 최종 수량도 정확하다`() {
        val template = couponTemplateRepository.save(CouponTemplate(name = "아메리카노", discountRate = 10))
        val campaign = couponCampaignRepository.save(
            CouponCampaign(
                couponTemplateId = template.id!!,
                totalQuantity = 2,
                issuedQuantity = 0,
                openAt = LocalDateTime.now().minusMinutes(1),
            ),
        )

        val threadCount = 2
        val successCount = AtomicInteger(0)
        val failCount = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(threadCount)
        val startGate = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)

        repeat(threadCount) { i ->
            executor.submit {
                startGate.await()
                try {
                    couponService.issueOptimistic(campaign.id!!, userId = i.toLong())
                    successCount.incrementAndGet()
                } catch (e: Exception) {
                    println("[PROBE] 실패: ${e::class.simpleName} - ${e.message}")
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
            "[PROBE] totalQuantity=2, 동시 요청=$threadCount, " +
                "성공=${successCount.get()}, 실패=${failCount.get()}, 최종 issuedQuantity=${finalCampaign.issuedQuantity}",
        )

        assertEquals(2, successCount.get(), "재고가 2개 남아있는데 2명 다 성공하지 못했다")
        assertEquals(0, failCount.get())
        assertEquals(2, finalCampaign.issuedQuantity, "최종 수량이 실제 성공 건수와 안 맞는다 — stale entity로 인한 오염 가능성")
    }
}
