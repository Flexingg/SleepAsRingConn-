package com.randallengineering.sleepasringconn.protocol

/** Port of OpenCircuit LiveHR.settled: publish median of last 5 locked values once >= 5 exist. */
class LiveHrSettler {
    private val trend = ArrayDeque<Int>()

    /** Feed a locked HR; returns the settled value or null if not yet settled. */
    @Synchronized
    fun add(hr: Int): Int? {
        trend.addLast(hr)
        while (trend.size > TREND_CAP) trend.removeFirst()
        return settled()
    }

    @Synchronized
    fun settled(): Int? {
        if (trend.size < SETTLE_COUNT) return null
        val window = trend.toList().takeLast(SETTLE_COUNT).sorted()
        return window[window.size / 2]
    }

    @Synchronized
    fun clear() = trend.clear()

    companion object {
        const val SETTLE_COUNT = 5
        const val TREND_CAP = 12
    }
}
