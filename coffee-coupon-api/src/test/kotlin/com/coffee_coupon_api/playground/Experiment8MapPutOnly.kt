package com.coffee_coupon_api.playground

import java.util.Hashtable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// 실험 8. get 없이 put만 여러 스레드에서 동시에 불러도 데이터가 사라지나?
// 4개 스레드가 서로 겹치지 않는 키를 10만 개씩 동시에 넣는다. 정상이면 size = 400,000.
private const val THREADS = 4
private const val PER_THREAD = 100_000

private fun run(name: String, map: MutableMap<Int, Int>) {
    // HashMap이 꼬이면 스레드가 끝나지 않는 루프에 빠질 수 있다. shutdownNow()의 인터럽트로는 멈추지 않으므로,
    // 데몬 스레드로 만들어 main이 끝나면 JVM도 종료되게 한다.
    val pool = Executors.newFixedThreadPool(THREADS) { r -> Thread(r).apply { isDaemon = true } }
    val start = CountDownLatch(1)
    val done = CountDownLatch(THREADS)
    repeat(THREADS) { t ->   // t: 스레드 번호(0~3). 스레드 0은 0~99,999, 스레드 1은 100,000~199,999 …
        pool.submit {
            start.await()
            try {
                for (i in 0 until PER_THREAD) map[t * PER_THREAD + i] = i
            } catch (e: Exception) {
                println("  [$name] 예외: $e")   // HashMap은 resize가 꼬이면 예외가 날 수도 있다
            } finally {
                done.countDown()
            }
        }
    }
    start.countDown()
    done.await(30, TimeUnit.SECONDS)
    pool.shutdownNow()
    val stuck = done.count   // 30초 안에 끝나지 않은 스레드 수
    val note = if (stuck > 0) " (끝나지 않은 스레드 ${stuck}개: 무한 루프 의심)" else ""
    println("[$name] 기대 size = ${THREADS * PER_THREAD}, 실제 size = ${map.size}$note")
}

fun main() {
    repeat(3) {
        run("HashMap", HashMap())
        run("Hashtable", Hashtable())
        run("ConcurrentHashMap", ConcurrentHashMap())
    }
}
