package com.coffee_coupon_api.service

import com.coffee_coupon_api.domain.CouponCampaign
import com.coffee_coupon_api.domain.CouponTemplate
import com.coffee_coupon_api.repository.CouponCampaignRepository
import com.coffee_coupon_api.repository.CouponTemplateRepository
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import javax.sql.DataSource
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionDefinition

@SpringBootTest
class ScratchRefreshSnapshotProbeTest {

    @Autowired
    lateinit var couponCampaignRepository: CouponCampaignRepository

    @Autowired
    lateinit var couponTemplateRepository: CouponTemplateRepository

    @Autowired
    lateinit var entityManager: EntityManager

    @Autowired
    lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    lateinit var dataSource: DataSource

    @Test
    fun `같은 트랜잭션 안에서 entityManager refresh가 외부 커밋을 실제로 보는가`() {
        val template = couponTemplateRepository.save(CouponTemplate(name = "probe", discountRate = 10))
        val campaign = couponCampaignRepository.save(
            CouponCampaign(couponTemplateId = template.id!!, totalQuantity = 10, issuedQuantity = 0),
        )

        // issueOptimistic과 똑같이, 우리가 직접 트랜잭션 경계를 하나 연다 (REPEATABLE READ)
        val tx = transactionManager.getTransaction(DefaultTransactionDefinition())
        try {
            val fetched = couponCampaignRepository.findById(campaign.id!!).orElseThrow()
            println("[PROBE] 최초 읽은 값: issuedQuantity=${fetched.issuedQuantity} version=${fetched.version}")

            // 외부 트랜잭션(진짜 별도 JDBC 커넥션, autocommit)이 즉시 커밋
            dataSource.connection.use { conn ->
                conn.autoCommit = true
                conn.prepareStatement(
                    "UPDATE coupon_campaign SET issued_quantity = issued_quantity + 1, version = version + 1 WHERE id = ?",
                ).use { stmt ->
                    stmt.setLong(1, campaign.id!!)
                    val updated = stmt.executeUpdate()
                    println("[PROBE] 외부 커밋 완료, 영향받은 행: $updated")
                }
            }

            entityManager.refresh(fetched)
            println("[PROBE] refresh() 후 값: issuedQuantity=${fetched.issuedQuantity} version=${fetched.version}")

            entityManager.refresh(fetched, LockModeType.PESSIMISTIC_READ)
            println("[PROBE] refresh(PESSIMISTIC_READ) 후 값: issuedQuantity=${fetched.issuedQuantity} version=${fetched.version}")
        } finally {
            transactionManager.commit(tx)
        }
    }
}
