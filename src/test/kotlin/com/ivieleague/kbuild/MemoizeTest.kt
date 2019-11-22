package com.ivieleague.kbuild

import org.junit.Assert.assertEquals
import org.junit.Test

class MemoizeTest {
    @Test
    fun testMemoize() {
        var runCount = 0
        fun memoizedAction(string: String) = memoize(string) {
            runCount++
            return@memoize string.reversed()
        }
        assertEquals(0, runCount)
        assertEquals("fdsa", memoizedAction("asdf"))
        assertEquals(1, runCount)
        assertEquals("olleh", memoizedAction("hello"))
        assertEquals(2, runCount)
        assertEquals("fdsa", memoizedAction("asdf"))
        assertEquals(2, runCount)
        assertEquals("olleh", memoizedAction("hello"))
        assertEquals(2, runCount)
    }
}