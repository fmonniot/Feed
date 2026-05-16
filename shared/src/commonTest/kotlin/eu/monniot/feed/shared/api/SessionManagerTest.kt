package eu.monniot.feed.shared.api

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionManagerTest {

    @Test
    fun initiallyNotLoggedIn() = runTest {
        val mgr = SessionManager()
        assertFalse(mgr.isLoggedIn.first())
    }

    @Test
    fun setLoggedInTrue() = runTest {
        val mgr = SessionManager()
        mgr.setLoggedIn(true)
        assertTrue(mgr.isLoggedIn.first())
    }

    @Test
    fun setLoggedInFalseAfterTrue() = runTest {
        val mgr = SessionManager(initial = true)
        mgr.setLoggedIn(false)
        assertFalse(mgr.isLoggedIn.first())
    }
}
