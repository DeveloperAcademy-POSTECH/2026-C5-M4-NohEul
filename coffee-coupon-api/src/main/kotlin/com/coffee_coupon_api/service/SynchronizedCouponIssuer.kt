package com.coffee_coupon_api.service

import com.coffee_coupon_api.domain.CouponIssue
import java.util.concurrent.ConcurrentHashMap
import org.springframework.stereotype.Service

// 트랜잭션 없이 캠페인별 락만 잡는 바깥 빈. @Transactional을 붙이면 안 된다:
// 그러면 커밋이 이 메서드 반환 뒤에 일어나서, 락이 커밋보다 먼저 풀린다.
// JVM 안에서만 유효한 락이라 서버를 여러 대 띄우면 서로를 막지 못한다.
@Service
class SynchronizedCouponIssuer(
    private val processor: SynchronizedCouponIssueProcessor,
) {
    // 캠페인 ID마다 락 객체 하나. 끝난 캠페인의 락은 지우지 않는다(스펙의 알려진 한계).
    private val locks = ConcurrentHashMap<Long, Any>()

    fun issue(couponCampaignId: Long, userId: Long): CouponIssue {
        // computeIfAbsent는 같은 키에 대해 원자적이라, 첫 요청 두 개가 동시에 와도 같은 락 객체를 받는다.
        val lock = locks.computeIfAbsent(couponCampaignId) { Any() }
        synchronized(lock) {
            return processor.issue(couponCampaignId, userId)
        }
    }
}
