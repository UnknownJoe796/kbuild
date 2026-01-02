package demo

import kotlin.test.Test
import kotlin.test.assertEquals

class MainTest {
    @Test
    fun `greet returns correct message`() {
        assertEquals("Hello, World!", greet("World"))
    }

    @Test
    fun `greet works with different names`() {
        assertEquals("Hello, Alice!", greet("Alice"))
        assertEquals("Hello, Bob!", greet("Bob"))
    }
}
