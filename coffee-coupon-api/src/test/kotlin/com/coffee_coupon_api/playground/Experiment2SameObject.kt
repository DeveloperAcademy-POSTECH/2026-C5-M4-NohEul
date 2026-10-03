package com.coffee_coupon_api.playground

import kotlin.concurrent.thread

// 실험 2. 왜 같은 객체를 넘겨야 할까?
// 한쪽만 synchronized를 쓰는 경우와, 둘 다 쓰지만 서로 다른 자물쇠를 잡는 경우를 차례로 돌린다.
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
    val lockA = Any()
    val lockB = Any()
    println("한쪽만 synchronized : ${run({ synchronized(lock) { count++ } }, { count++ })}")              // ← t2는 깃발을 확인하지 않음
    println("다른 자물쇠         : ${run({ synchronized(lockA) { count++ } }, { synchronized(lockB) { count++ } })}")  // ← 다른 자물쇠
}
