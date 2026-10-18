package com.coffee_coupon_api.playground

import kotlin.concurrent.thread

// 실험 1. synchronized가 없으면 정말 깨지나?
// 두 스레드가 같은 변수를 각각 10만 번 +1 한다. 락 없이 한 번, 같은 자물쇠로 한 번 돌린다.
private var count = 0

private fun run(t1Body: () -> Unit, t2Body: () -> Unit): Int {
    count = 0
    val t1 = thread { repeat(100_000) { t1Body() } }
    val t2 = thread { repeat(100_000) { t2Body() } }
    t1.join(); t2.join()
    return count
}

fun main() {
    val lock = Any()
    println("락 없음        : ${run({ count++ }, { count++ })}")                                     // 예측: 200,000?
    println("같은 자물쇠    : ${run({ synchronized(lock) { count++ } }, { synchronized(lock) { count++ } })}")
}
