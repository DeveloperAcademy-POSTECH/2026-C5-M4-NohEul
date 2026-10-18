package com.coffee_coupon_api.service

import com.coffee_coupon_api.domain.CouponCampaign
import com.coffee_coupon_api.domain.CouponTemplate
import com.coffee_coupon_api.repository.CouponCampaignRepository
import com.coffee_coupon_api.repository.CouponTemplateRepository
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

// JVM 락의 "위치"와 "범위"가 결과를 어떻게 바꾸는지 보는 실험. 결과가 확률적이라 assertion 없이 출력만 한다.
@SpringBootTest
class ScratchJvmLockExperimentTest {

    @Autowired lateinit var couponService: CouponService
    @Autowired lateinit var processor: SynchronizedCouponIssueProcessor
    @Autowired lateinit var synchronizedCouponIssuer: SynchronizedCouponIssuer
    @Autowired lateinit var txBeans: ExperimentTxBeans
    @Autowired lateinit var couponCampaignRepository: CouponCampaignRepository
    @Autowired lateinit var couponTemplateRepository: CouponTemplateRepository

    // ---------- 결과: 재고 1장, 30명, 5라운드 ----------

    @Test
    fun `E0~E3 라운드별 성공 수`() {
        val lockInsideTx = Any()
        val lockA = Any()
        val lockB = Any()

        runRounds("E0 락 없음") { id, user -> couponService.issueNoLock(id, user) }
        runRounds("E1 트랜잭션 안 synchronized") { id, user -> txBeans.lockInsideTx(lockInsideTx, id, user, log = null) }
        runRounds("E2 트랜잭션 밖 synchronized") { id, user -> synchronizedCouponIssuer.issue(id, user) }
        runRounds("E3 락 객체 두 개") { id, user ->
            val lock = if (user % 2 == 0L) lockA else lockB   // 서버 두 대처럼 절반씩 다른 락
            synchronized(lock) { processor.issue(id, user) }
        }
    }

    // ---------- 원인: 락 획득 / 락 해제 / 커밋 순서 (스레드 3개) ----------

    @Test
    fun `E1 순서 로그 - 트랜잭션 안 synchronized`() {
        val log = OrderLog()
        val lock = Any()
        val campaign = seedOpenCampaign()
        runConcurrently(3) { i -> catching { txBeans.lockInsideTx(lock, campaign.id!!, i.toLong(), log) } }
        log.print("E1 트랜잭션 안 synchronized")
    }

    @Test
    fun `E2 순서 로그 - 트랜잭션 밖 synchronized`() {
        val log = OrderLog()
        val lock = Any()
        val campaign = seedOpenCampaign()
        runConcurrently(3) { i ->
            catching {
                synchronized(lock) {
                    log.add("T$i 락 획득 (읽은 재고=${issuedQuantity(campaign.id!!)})")
                    try {
                        txBeans.issueWithCommitLog(campaign.id!!, i.toLong(), log, "T$i")
                    } finally {
                        log.add("T$i 락 해제")
                    }
                }
            }
        }
        log.print("E2 트랜잭션 밖 synchronized")
    }

    // ---------- 테스트 전용 빈 ----------

    @TestConfiguration
    class Config {
        @Bean
        fun experimentTxBeans(processor: SynchronizedCouponIssueProcessor, repo: CouponCampaignRepository) =
            ExperimentTxBeans(processor, repo)
    }

    // @Bean으로 등록해서 kotlin-spring 플러그인이 자동으로 open 해 주지 않는다.
    // 프록시(CGLIB 하위 클래스)가 만들어지도록 클래스와 메서드를 직접 open으로 연다.
    open class ExperimentTxBeans(
        private val processor: SynchronizedCouponIssueProcessor,
        private val repo: CouponCampaignRepository,
    ) {
        // E1: 틀린 버전. 트랜잭션이 바깥이라, Processor는 이 트랜잭션에 합류하고 커밋은 이 메서드 반환 뒤에 일어난다.
        @Transactional
        open fun lockInsideTx(lock: Any, campaignId: Long, userId: Long, log: OrderLog?) {
            log?.let { registerCommitLog(it, "T$userId") }
            synchronized(lock) {
                log?.add("T$userId 락 획득 (읽은 재고=${repo.findById(campaignId).orElseThrow().issuedQuantity})")
                try {
                    processor.issue(campaignId, userId)
                } finally {
                    log?.add("T$userId 락 해제")
                }
            }
        }

        // E2 순서 로그용: Processor와 같은 트랜잭션 범위에 커밋 콜백만 붙인다.
        @Transactional
        open fun issueWithCommitLog(campaignId: Long, userId: Long, log: OrderLog, name: String) {
            registerCommitLog(log, name)
            processor.issue(campaignId, userId)
        }

        private fun registerCommitLog(log: OrderLog, name: String) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCompletion(status: Int) {
                    log.add(if (status == TransactionSynchronization.STATUS_COMMITTED) "$name 커밋 완료" else "$name 롤백")
                }
            })
        }
    }

    class OrderLog {
        private val seq = AtomicInteger(0)
        private val entries = ConcurrentLinkedQueue<String>()
        fun add(message: String) { entries.add("${seq.incrementAndGet()}. $message") }
        fun print(title: String) { println("[$title]"); entries.forEach { println("  $it") } }
    }

    // ---------- 헬퍼 ----------

    private fun runRounds(name: String, rounds: Int = 5, threadCount: Int = 30, issue: (Long, Long) -> Unit) {
        val results = (1..rounds).map {
            val campaign = seedOpenCampaign()
            val success = AtomicInteger(0)
            runConcurrently(threadCount) { i -> if (catching { issue(campaign.id!!, i.toLong()) }) success.incrementAndGet() }
            success.get()
        }
        println("[$name] 라운드별 성공 수 = $results (재고 1장, ${threadCount}명)")
    }

    private fun catching(action: () -> Unit): Boolean = try { action(); true } catch (e: Exception) { false }

    private fun issuedQuantity(campaignId: Long) = couponCampaignRepository.findById(campaignId).orElseThrow().issuedQuantity

    private fun seedOpenCampaign(): CouponCampaign {
        val template = couponTemplateRepository.save(CouponTemplate(name = "아메리카노", discountRate = 10))
        return couponCampaignRepository.save(
            CouponCampaign(couponTemplateId = template.id!!, totalQuantity = 1, issuedQuantity = 0, openAt = LocalDateTime.now().minusMinutes(1)),
        )
    }

    private fun runConcurrently(threadCount: Int, action: (Int) -> Unit) {
        val executor = Executors.newFixedThreadPool(threadCount)
        val startGate = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        repeat(threadCount) { i ->
            executor.submit {
                startGate.await()
                try { action(i) } finally { doneLatch.countDown() }
            }
        }
        startGate.countDown()
        doneLatch.await(30, TimeUnit.SECONDS)
        executor.shutdown()
    }
}
