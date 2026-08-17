package com.coffee_coupon_api.repository

import com.coffee_coupon_api.domain.CouponCampaign
import jakarta.persistence.LockModeType
import java.time.LocalDateTime
import java.util.Optional
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface CouponCampaignRepository : JpaRepository<CouponCampaign, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CouponCampaign c where c.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long): Optional<CouponCampaign>

    // 스칼라 프로젝션이어야 한다. 엔티티로 읽으면 영속성 컨텍스트에 올라가고,
    // 뒤따르는 findByIdForUpdate가 1차 캐시의 stale 인스턴스를 돌려줘 비관적 락이 무력화된다.
    @Query("select c.openAt from CouponCampaign c where c.id = :id")
    fun findOpenAtById(@Param("id") id: Long): LocalDateTime?
}
