package com.coffee_coupon_api.repository

import com.coffee_coupon_api.domain.CouponCampaign
import jakarta.persistence.LockModeType
import java.time.LocalDateTime
import java.util.Optional
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface CouponCampaignRepository : JpaRepository<CouponCampaign, Long> {

    // MySQL에서는 SELECT ... FOR UPDATE로 번역되어, 트랜잭션이 끝날 때까지 해당 행을 배타적으로 잠근다.
    // (PESSIMISTIC_READ는 쓰기만 막고 읽기는 허용, OPTIMISTIC은 DB 락 없이 @Version으로 충돌만 감지)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CouponCampaign c where c.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long): Optional<CouponCampaign>

    // 스칼라 프로젝션이어야 한다. 엔티티로 읽으면 영속성 컨텍스트에 올라가고,
    // 뒤따르는 findByIdForUpdate가 1차 캐시의 stale 인스턴스를 돌려줘 비관적 락이 무력화된다.
    @Query("select c.openAt from CouponCampaign c where c.id = :id")
    fun findOpenAtById(@Param("id") id: Long): LocalDateTime?

    // 락 없음(issueNoLock)·synchronized(SynchronizedCouponIssueProcessor) 전용. 엔티티 기반 save()는 CouponCampaign에 붙은 @Version 때문에 자동으로
    // 버전 체크를 받아버려서, "락 없음" 시나리오가 의도치 않게 보호받게 된다. HQL 벌크 UPDATE는
    // "versioned" 키워드를 명시하지 않는 한 버전 컬럼을 건드리지 않으므로, 이 쿼리는 완전히
    // 무방비 상태의 증가를 재현한다.
    @Modifying
    @Query("update CouponCampaign c set c.issuedQuantity = c.issuedQuantity + 1 where c.id = :id")
    fun incrementIssuedQuantityRaw(@Param("id") id: Long): Int
}
