package samples.reactiveserver

import kotlin.test.Test
import kotlin.test.assertEquals

class ServerTest {

    @Test
    fun `greet returns correct message`() {
        assertEquals("Hello, World!", greet("World"))
        assertEquals("Hello, Alice!", greet("Alice"))
    }

    @Test
    fun `fibonacci computes correctly`() {
        assertEquals(0, fibonacci(0))
        assertEquals(1, fibonacci(1))
        assertEquals(1, fibonacci(2))
        assertEquals(2, fibonacci(3))
        assertEquals(5, fibonacci(5))
        assertEquals(55, fibonacci(10))
    }

    @Test
    fun `fibonacci handles larger numbers`() {
        assertEquals(6765, fibonacci(20))
        assertEquals(832040, fibonacci(30))
    }
}
