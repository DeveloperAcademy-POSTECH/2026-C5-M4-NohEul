package com.coffee_coupon_api.playground

import kotlin.concurrent.thread

// 실험 1. synchronized가 없으면 정말 깨지나?
// 두 스레드가 같은 변수를 각각 10만 번 +1 한다.
private var count = 0

fun main() {
    val t1 = thread { repeat(100_000) { count++ } }
    val t2 = thread { repeat(100_000) { count++ } }
    t1.join(); t2.join()
    println("count = $count")   // 예측: 200,000?
}
