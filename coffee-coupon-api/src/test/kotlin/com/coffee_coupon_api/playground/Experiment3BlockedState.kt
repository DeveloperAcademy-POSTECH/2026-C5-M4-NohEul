package com.coffee_coupon_api.playground

import kotlin.concurrent.thread

// 실험 3. 기다리는 스레드는 무슨 상태일까?
// t1이 락을 잡고 3초 동안 놓지 않는 사이, 같은 락을 기다리는 t2의 상태를 찍어본다.
fun main() {
    val lock = Any()
    val t1 = thread(name = "t1") {
        synchronized(lock) {
            println("t1: 락 잡고 3초 대기")
            Thread.sleep(3000)
        }
    }
    Thread.sleep(100)                   // t1이 먼저 잡도록
    val t2 = thread(name = "t2") {
        synchronized(lock) { println("t2: 락 획득!") }
    }
    Thread.sleep(500)
    println("t2 상태 = ${t2.state}")   // 예측: RUNNABLE? WAITING? BLOCKED?
    t1.join(); t2.join()
}
