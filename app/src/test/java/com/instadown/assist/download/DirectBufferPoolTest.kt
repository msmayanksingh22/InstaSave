package com.instadown.assist.download

import org.junit.Assert.*
import org.junit.Test

class DirectBufferPoolTest {

    @Test
    fun testBufferPoolBorrowAndRelease() {
        val buffer = DirectBufferPool.borrow()
        assertNotNull(buffer)
        assertTrue(buffer.isDirect)
        assertEquals(64 * 1024, buffer.capacity())
        assertEquals(0, buffer.position())

        buffer.put(0x42.toByte())
        assertEquals(1, buffer.position())

        DirectBufferPool.release(buffer)

        val borrowedAgain = DirectBufferPool.borrow()
        assertEquals(0, borrowedAgain.position())
        DirectBufferPool.release(borrowedAgain)
    }

    @Test
    fun testBufferPoolMultipleBuffers() {
        val buffers = (1..5).map { DirectBufferPool.borrow() }
        assertEquals(5, buffers.size)
        buffers.forEach {
            assertTrue(it.isDirect)
            assertEquals(64 * 1024, it.capacity())
        }
        buffers.forEach { DirectBufferPool.release(it) }
    }
}
