package com.coffee_coupon_api.service

import com.coffee_coupon_api.domain.Coupon
import com.coffee_coupon_api.repository.CouponRepository
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class CouponServiceConcurrencyTest {

    @Autowired
    lateinit var couponService: CouponService

    @Autowired
    lateinit var couponRepository: CouponRepository

    @Test
    fun `재고 1개짜리 쿠폰에 N명이 동시에 요청하면 락 없이는 재고가 깨진다`() {
        val coupon = couponRepository.save(Coupon(name = "아메리카노", totalQuantity = 1, issuedQuantity = 0))
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
                    couponService.issue(coupon.id!!, userId = i.toLong())
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

        val finalCoupon = couponRepository.findById(coupon.id!!).orElseThrow()
        println(
            "[재현 결과] totalQuantity=1, 동시 요청=$threadCount, " +
                "성공=${successCount.get()}, 실패=${failCount.get()}, 최종 issuedQuantity=${finalCoupon.issuedQuantity}",
        )
    }
}
