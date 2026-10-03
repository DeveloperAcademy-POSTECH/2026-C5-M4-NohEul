package com.coffee_coupon_api.playground

import kotlin.concurrent.thread

// 실험 3 덤. 재진입(reentrant)
// 깃발을 이미 쥔 스레드가 같은 깃발을 한 번 더 요청하면, 스스로 막힐까?
fun main() {
    val lock = Any()
    val t = thread(name = "t") {
        synchronized(lock) {
            println("바깥 블록 진입 (깃발 1번 잡음)")
            synchronized(lock) {
                println("안쪽 블록 진입 (같은 깃발을 또 잡음)")
            }
            println("안쪽 블록 나옴")
        }
        println("바깥 블록 나옴")
    }

    // 스스로 막혀서 영원히 안 끝날 수도 있으니, 2초만 기다려 본다
    t.join(2000)
    if (t.isAlive) {
        println("2초가 지나도 안 끝남 → 스스로 막혔다 (t 상태 = ${t.state})")
    } else {
        println("끝까지 실행됨")
    }
}
