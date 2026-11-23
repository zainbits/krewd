package app.krewd.data

import app.krewd.constants.Timing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteRaceTest {
    private val home = "https://192.168.0.134:8800"
    private val tailscale = "https://bottle.tail1234.ts.net:8800"
    private val domain = "https://krewd.example.com"

    @Test
    fun homeAddressesComeFirstThenTheOwnersOrder() {
        assertEquals(listOf(home, tailscale, domain), ConnectionSettings.ranked(listOf(tailscale, home, domain)))
        assertEquals(listOf(home, "https://bottle.local:8800", domain, tailscale),
            ConnectionSettings.ranked(listOf(domain, home, tailscale, "https://bottle.local:8800")))
        assertTrue(ConnectionSettings.isLocal(home))
        assertTrue(!ConnectionSettings.isLocal("https://100.71.18.45:8800"))
    }

    @Test
    fun aLowerRankedAnswerWaitsForBetterOnes() {
        val pick = RacePick<String>(2) // 0: home, 1: tailscale
        pick.done(1, Result.success("tailscale"))
        assertNull(pick.winner()) // home may still answer within the grace time
        assertEquals("tailscale", pick.best())
        pick.done(0, Result.failure(java.io.IOException("no route")))
        assertEquals("tailscale", pick.winner())
    }

    @Test
    fun theBestRankedAnswerWinsAtOnce() {
        val pick = RacePick<String>(2)
        pick.done(0, Result.success("home"))
        assertEquals("home", pick.winner())
    }

    @Test
    fun whenAllFailTheLastRankedErrorIsReported() {
        val pick = RacePick<String>(2)
        pick.done(1, Result.failure(java.io.IOException("tailscale down")))
        pick.done(0, Result.failure(java.io.IOException("no route")))
        assertTrue(pick.allDone())
        assertNull(pick.best())
        assertEquals("tailscale down", pick.error()?.message)
    }

    @Test
    fun backoffGrowsHasJitterAndCaps() {
        val delays = longArrayOf(0, 1_000, 2_000)
        assertEquals(0L, Timing.backoff(delays, 0))
        repeat(50) {
            val d = Timing.backoff(delays, 1)
            assertTrue(d in 1_000..1_250)
            assertTrue(Timing.backoff(delays, 9) in 2_000..2_500)
        }
    }

    @Test
    fun refusalsAreNotRetried() {
        assertTrue(ApiException(400, "bad").refusal)
        assertTrue(ApiException(404, "gone").refusal)
        assertTrue(!ApiException(401, "sign in").refusal)
        assertTrue(!ApiException(429, "slow down").refusal)
        assertTrue(!ApiException(503, "restarting").refusal)
    }
}
