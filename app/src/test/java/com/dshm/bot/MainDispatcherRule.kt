package com.dshm.bot

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** 把 `Dispatchers.Main` 换成测试调度器，用于测 ViewModel。 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val testDispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

internal object TestTime {
    val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    val clock: Clock = Clock.fixed(Instant.parse("2026-11-01T13:53:00Z"), zone)
    val locale: Locale = Locale.SIMPLIFIED_CHINESE
}
