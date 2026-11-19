package com.instadown.assist.overlay

import com.instadown.assist.resolver.TierCircuitBreaker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ResilientHudStateMachineTest {

    @Before
    fun setup() {
        ResilientHudBus.dismiss(force = true)
        TierCircuitBreaker.resetAll()
    }

    @Test
    fun testMonotonicOperationIdLeaseRejectsStaleEmissions() {
        val op1 = ResilientHudBus.startOperation()
        assertTrue("Operation ID must be positive", op1 > 0)

        val emitted1 = ResilientHudBus.emit(ResilientHudState.LinkDetected(op1, "Post 1"))
        assertTrue("Active operation must be accepted", emitted1)
        assertEquals(op1, ResilientHudBus.state.value.operationId)

        // Start second operation
        val op2 = ResilientHudBus.startOperation()
        assertTrue("Subsequent operation ID must increment monotonically", op2 > op1)

        val emitted2 = ResilientHudBus.emit(ResilientHudState.LinkDetected(op2, "Post 2"))
        assertTrue(emitted2)
        assertEquals(op2, ResilientHudBus.state.value.operationId)

        // Try to emit with stale op1
        val staleEmitted = ResilientHudBus.emit(ResilientHudState.Resolving(op1, "Stale resolving"))
        assertFalse("Stale operation must be rejected by ResilientHudBus", staleEmitted)
        assertEquals("State must remain on active op2", op2, ResilientHudBus.state.value.operationId)
    }

    @Test
    fun testDismissGuardsAgainstStaleOperationsUnlessForced() {
        val op1 = ResilientHudBus.startOperation()
        ResilientHudBus.emit(ResilientHudState.LinkDetected(op1, "Post 1"))

        val op2 = ResilientHudBus.startOperation()
        ResilientHudBus.emit(ResilientHudState.LinkDetected(op2, "Post 2"))

        // op1 tries to dismiss -> must NOT dismiss active op2
        ResilientHudBus.dismiss(operationId = op1)
        assertTrue("State must remain visible because op1 is stale", ResilientHudBus.state.value !is ResilientHudState.Hidden)
        assertEquals(op2, ResilientHudBus.state.value.operationId)

        // Matching op2 dismiss -> must transition to Hidden
        ResilientHudBus.dismiss(operationId = op2)
        assertTrue("Matching operationId must dismiss to Hidden", ResilientHudBus.state.value is ResilientHudState.Hidden)

        // Force dismiss works at any time
        ResilientHudBus.emit(ResilientHudState.LinkDetected(op2, "Re-opened"))
        ResilientHudBus.dismiss(force = true)
        assertTrue("Force dismiss must transition to Hidden regardless of opId", ResilientHudBus.state.value is ResilientHudState.Hidden)
    }

    @Test
    fun testEveryStateDeclaresStrictFiniteTtl() {
        val op = ResilientHudBus.startOperation()

        val link = ResilientHudState.LinkDetected(op, "link")
        val resolving = ResilientHudState.Resolving(op, "stage")
        val downloading = ResilientHudState.Downloading(op, 1, 1, 0L, 0L, 50)
        val success = ResilientHudState.Success(op, "ok")
        val error = ResilientHudState.Error(op, "err")

        assertTrue("LinkDetected TTL must be finite (>0)", link.maxTtlMs in 1000L..10000L)
        assertTrue("Resolving TTL must be finite (>0)", resolving.maxTtlMs in 1000L..15000L)
        assertTrue("Downloading TTL must be finite (>0)", downloading.maxTtlMs in 1000L..30000L)
        assertTrue("Success TTL must be finite (>0)", success.maxTtlMs in 1000L..5000L)
        assertTrue("Error TTL must be finite (>0)", error.maxTtlMs in 1000L..5000L)
    }

    @Test
    fun testTierCircuitBreakerTripsAndResets() {
        assertFalse("Circuit breaker must start untripped", TierCircuitBreaker.isTripped("Tier0_GraphQL"))

        TierCircuitBreaker.trip("Tier0_GraphQL")
        assertTrue("Circuit breaker must be tripped after trip()", TierCircuitBreaker.isTripped("Tier0_GraphQL"))

        TierCircuitBreaker.reset("Tier0_GraphQL")
        assertFalse("Circuit breaker must be untripped after reset()", TierCircuitBreaker.isTripped("Tier0_GraphQL"))
    }
}
