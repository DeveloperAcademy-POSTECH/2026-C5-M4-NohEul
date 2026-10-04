package com.coffee_coupon_api.playground

import kotlin.concurrent.thread

// 실험 6. 락은 풀렸는데 데이터는 깨질 수 있을까?
// A에서 100을 빼고 B에 100을 더하는 "이체"를 synchronized로 감싼다.
// 빼고 나서 더하기 전에 예외를 던지면, 락은 풀려도 a + b의 합(1000)이 유지될까?
private var a = 1000
private var b = 0

fun main() {
    val lock = Any()

    println("이체 전: a = $a, b = $b, 합 = ${a + b}")

    try {
        synchronized(lock) {
            a -= 100                                   // ① A에서 뺌
            throw IllegalStateException("이체 도중 실패")  // ② 예외 발생
            @Suppress("UNREACHABLE_CODE")
            b += 100                                   // ③ 실행되지 않음
        }
    } catch (e: IllegalStateException) {
        println("main: 예외 잡음 (${e.message})")
    }

    // 락이 풀렸는지, 그리고 다음 스레드가 보는 상태가 어떤지 확인한다
    val t = thread(name = "t") {
        synchronized(lock) {
            println("t: 락 획득! a = $a, b = $b, 합 = ${a + b}")
        }
    }
    t.join(2000)
    if (t.isAlive) {
        println("2초가 지나도 t가 못 잡음 → 락이 안 풀렸다")
    }
}
