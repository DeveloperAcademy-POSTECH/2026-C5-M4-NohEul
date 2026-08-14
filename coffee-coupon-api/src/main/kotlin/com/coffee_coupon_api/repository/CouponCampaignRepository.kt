package com.coffee_coupon_api.repository

import com.coffee_coupon_api.domain.CouponCampaign
import jakarta.persistence.LockModeType
import java.util.Optional
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface CouponCampaignRepository : JpaRepository<CouponCampaign, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CouponCampaign c where c.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long): Optional<CouponCampaign>
}
