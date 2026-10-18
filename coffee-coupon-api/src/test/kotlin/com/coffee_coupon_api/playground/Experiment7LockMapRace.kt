package com.coffee_coupon_api.playground

import java.util.Collections
import java.util.Hashtable
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// 실험 7. 락 보관함을 어떤 맵으로, 어떻게 꺼내야 같은 캠페인에 락 객체가 하나만 생기나?
// 32개 스레드가 같은 키(캠페인 1)로 동시에 "없으면 만들어 넣기"를 하고, 받은 락 객체가 몇 개인지 센다.
// 정상이면 1개. 이걸 2,000라운드 반복한다.
private const val THREADS = 32
private const val ROUNDS = 2_000
private const val KEY = 1L

// 한 라운드: 32개 스레드를 출발선에 세웠다가 동시에 출발시키고, 받은 락 객체를 모아 서로 다른 객체 수를 센다.
private fun countLocks(getOrCreate: () -> Any): Int {
    val pool = Executors.newFixedThreadPool(THREADS)
    val start = CountDownLatch(1)
    val done = CountDownLatch(THREADS)
    // equals가 아니라 참조(===)로 비교해야 "같은 객체인가"를 셀 수 있다
    val seen = Collections.synchronizedSet(Collections.newSetFromMap(IdentityHashMap<Any, Boolean>()))
    repeat(THREADS) {
        pool.submit {
            start.await()
            try { seen.add(getOrCreate()) } finally { done.countDown() }
        }
    }
    start.countDown()
    done.await(10, TimeUnit.SECONDS)
    pool.shutdown()
    return seen.size
}

private fun run(name: String, newMap: () -> MutableMap<Long, Any>, getOrCreate: (MutableMap<Long, Any>) -> Any) {
    var broken = 0
    var maxLocks = 0
    repeat(ROUNDS) {
        val map = newMap()   // 라운드마다 빈 보관함에서 시작
        val locks = countLocks { getOrCreate(map) }
        if (locks > 1) broken++
        if (locks > maxLocks) maxLocks = locks
    }
    println("[$name] $ROUNDS 라운드 중 락이 2개 이상 생긴 라운드 = $broken, 한 라운드 최대 락 개수 = $maxLocks")
}

fun main() {
    // 확인 후 넣기: ① get → ② 없으면 put. 두 번의 호출 사이에 틈이 있다
    run("HashMap, 확인 후 넣기", { HashMap() }) { m -> m[KEY] ?: Any().also { m[KEY] = it } }
    run("Hashtable, 확인 후 넣기", { Hashtable() }) { m -> m[KEY] ?: Any().also { m[KEY] = it } }
    run("ConcurrentHashMap, 확인 후 넣기", { ConcurrentHashMap() }) { m -> m[KEY] ?: Any().also { m[KEY] = it } }

    // 한 번의 호출: 확인과 넣기를 맵이 한 덩어리로 처리한다
    run("Hashtable, computeIfAbsent", { Hashtable() }) { m -> m.computeIfAbsent(KEY) { Any() } }
    run("ConcurrentHashMap, computeIfAbsent", { ConcurrentHashMap() }) { m -> m.computeIfAbsent(KEY) { Any() } }

    // Kotlin getOrPut: 실제 객체가 아니라 컴파일 시점의 변수 타입으로 어떤 버전이 불릴지 정해진다
    run("ConcurrentHashMap 타입으로 getOrPut", { ConcurrentHashMap() }) { m -> (m as ConcurrentHashMap<Long, Any>).getOrPut(KEY) { Any() } }
    run("MutableMap 타입으로 getOrPut (실제는 ConcurrentHashMap)", { ConcurrentHashMap() }) { m -> m.getOrPut(KEY) { Any() } }
}
