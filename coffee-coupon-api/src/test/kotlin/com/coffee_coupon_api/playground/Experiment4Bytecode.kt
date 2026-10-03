package com.coffee_coupon_api.playground

import kotlin.concurrent.thread

// 실험 4. synchronized는 바이트코드에서 어떻게 보일까?
// 이 클래스의 inc()를 바이트코드로 열어서 monitorenter / monitorexit을 찾아본다.
class SyncCounter {
    private val lock = Any()
    var count = 0

    fun inc() {
        synchronized(lock) { count++ }
    }
}

// 덤: 바이트코드에서 본 것을 실행으로 확인한다.
// synchronized 블록 안에서 예외가 나도 락이 풀리는가?
fun main() {
    val lock = Any()

    try {
        synchronized(lock) {
            println("main: 락 잡음 → 예외 발생!")
            throw IllegalStateException("일부러 터뜨린 예외")
        }
    } catch (e: IllegalStateException) {
        println("main: 예외 잡음 (${e.message})")
    }

    // 예외로 블록을 빠져나왔을 때 락이 안 풀렸다면, 다른 스레드는 영원히 못 잡는다
    val t = thread(name = "t") {
        synchronized(lock) { println("t: 락 획득!") }
    }
    t.join(2000)
    if (t.isAlive) {
        println("2초가 지나도 t가 못 잡음 → 예외 때문에 락이 안 풀렸다 (t 상태 = ${t.state})")
    } else {
        println("t가 락을 잡았다 → 예외가 나도 락이 풀렸다")
    }
}
