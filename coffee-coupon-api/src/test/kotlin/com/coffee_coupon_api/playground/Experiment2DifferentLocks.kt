package com.coffee_coupon_api.playground

import kotlin.concurrent.thread

// 실험 2. 같은 자물쇠 vs 다른 자물쇠
// 두 스레드가 모두 synchronized를 쓰지만, 서로 다른 lock 객체를 잡는다.
private var count = 0

fun main() {
    val lockA = Any()
    val lockB = Any()
    val t1 = thread { repeat(100_000) { synchronized(lockA) { count++ } } }
    val t2 = thread { repeat(100_000) { synchronized(lockB) { count++ } } }  // ← 다른 자물쇠
    t1.join(); t2.join()
    println("count = $count")   // 예측: ?
}
