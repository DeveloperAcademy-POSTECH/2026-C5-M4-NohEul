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
import org.junit.jupiter.api.Assertions.assertTrue
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

    @Autowired
    lateinit var synchronizedCouponIssuer: SynchronizedCouponIssuer

    @Test
    fun `비관적 락을 걸면 재고 1개짜리 쿠폰에 N명이 동시에 요청해도 1명만 성공한다`() {
        val campaign = seedOpenCampaign(totalQuantity = 1)
        val threadCount = 30
        val successCount = AtomicInteger(0)
        val failCount = AtomicInteger(0)

        runConcurrently(threadCount) { i ->
            try {
                couponService.issuePessimistic(campaign.id!!, userId = i.toLong())
                successCount.incrementAndGet()
            } catch (e: Exception) {
                failCount.incrementAndGet()
            }
        }

        val finalCampaign = couponCampaignRepository.findById(campaign.id!!).orElseThrow()
        println(
            "[비관적 락] totalQuantity=1, 동시 요청=$threadCount, " +
                "성공=${successCount.get()}, 실패=${failCount.get()}, 최종 issuedQuantity=${finalCampaign.issuedQuantity}",
        )

        assertEquals(1, successCount.get())
        assertEquals(threadCount - 1, failCount.get())
        assertEquals(1, finalCampaign.issuedQuantity)
    }

    @Test
    fun `락이 없으면 재고 1개짜리 쿠폰에 N명이 동시에 요청할 때 과발급된다`() {
        val campaign = seedOpenCampaign(totalQuantity = 1)
        val threadCount = 30
        val successCount = AtomicInteger(0)
        val failCount = AtomicInteger(0)

        runConcurrently(threadCount) { i ->
            try {
                couponService.issueNoLock(campaign.id!!, userId = i.toLong())
                successCount.incrementAndGet()
            } catch (e: Exception) {
                failCount.incrementAndGet()
            }
        }

        val finalCampaign = couponCampaignRepository.findById(campaign.id!!).orElseThrow()
        println(
            "[락 없음] totalQuantity=1, 동시 요청=$threadCount, " +
                "성공=${successCount.get()}, 실패=${failCount.get()}, 최종 issuedQuantity=${finalCampaign.issuedQuantity}",
        )

        // successCount는 실제로 저장된 CouponIssue 행 수와 정확히 같다(예외 없이 끝난 시도만 카운트하므로) —
        // 재고 1개인데 1명보다 많이 성공했다는 것 자체가 과발급의 직접적인 증거다.
        assertTrue(successCount.get() > 1, "락이 없는데도 1명만 성공했다 — 이 프로젝트가 재현하려는 race condition이 이번 실행에서는 안 터진 것")

        // 참고: finalCampaign.issuedQuantity(카운터 필드)는 successCount와 일치할 거라 기대하면 안 된다.
        // 여러 스레드가 동시에 issuedQuantity=0을 읽고 각자 +1해서 저장하는 lost update가 함께 일어나서,
        // 실제 성공 건수(successCount)보다 최종 카운터 값이 더 작게 나올 수 있다 — 이것도 락 없음의 또 다른 증상이다.
    }

    @Test
    fun `synchronized 락을 트랜잭션 바깥에서 걸면 재고 1개짜리 쿠폰에 N명이 동시에 요청해도 1명만 성공한다`() {
        val campaign = seedOpenCampaign(totalQuantity = 1)
        val threadCount = 30
        val successCount = AtomicInteger(0)
        val failCount = AtomicInteger(0)

        runConcurrently(threadCount) { i ->
            try {
                synchronizedCouponIssuer.issue(campaign.id!!, userId = i.toLong())
                successCount.incrementAndGet()
            } catch (e: Exception) {
                failCount.incrementAndGet()
            }
        }

        val finalCampaign = couponCampaignRepository.findById(campaign.id!!).orElseThrow()
        println(
            "[synchronized] totalQuantity=1, 동시 요청=$threadCount, " +
                "성공=${successCount.get()}, 실패=${failCount.get()}, 최종 issuedQuantity=${finalCampaign.issuedQuantity}",
        )

        assertEquals(1, successCount.get())
        assertEquals(threadCount - 1, failCount.get())
        assertEquals(1, finalCampaign.issuedQuantity)
    }

    private fun seedOpenCampaign(totalQuantity: Int): CouponCampaign {
        val template = couponTemplateRepository.save(CouponTemplate(name = "아메리카노", discountRate = 10))
        return couponCampaignRepository.save(
            CouponCampaign(
                couponTemplateId = template.id!!,
                totalQuantity = totalQuantity,
                issuedQuantity = 0,
                openAt = LocalDateTime.now().minusMinutes(1),
            ),
        )
    }

    private fun runConcurrently(threadCount: Int, action: (Int) -> Unit) {
        val executor = Executors.newFixedThreadPool(threadCount)
        val startGate = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)

        repeat(threadCount) { i ->
            executor.submit {
                startGate.await()
                try {
                    action(i)
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        startGate.countDown()
        doneLatch.await(10, TimeUnit.SECONDS)
        executor.shutdown()
    }
}
