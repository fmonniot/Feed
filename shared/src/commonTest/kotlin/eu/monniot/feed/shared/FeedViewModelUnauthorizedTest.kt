package eu.monniot.feed.shared

import com.russhwolf.settings.Settings
import eu.monniot.feed.shared.api.AuthApi
import eu.monniot.feed.shared.api.ServerUrlStore
import eu.monniot.feed.shared.api.SessionManager
import eu.monniot.feed.shared.data.UserPrefs
import eu.monniot.feed.shared.test.FakeFeedRepository
import eu.monniot.feed.shared.test.InMemorySettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun noopRepo(): FeedRepository = FakeFeedRepository()

/**
 * Verifies that a 401 Unauthorized response causes [FeedViewModel] to clear the
 * session (#34), redirecting both clients to login.
 *
 * Tests 1–3 call [FeedViewModel.onApiError] directly (it is `internal` for this
 * reason). The [ClientRequestException] is obtained via a direct `client.get()`
 * suspension call inside [runTest] (not inside `launch {}`), so we get a real
 * [HttpResponse] from [MockEngine] that satisfies all Ktor 3 invariants without
 * needing to fake the private `call` or `rawContent` properties.
 *
 * Test 4 exercises the login path end-to-end via MockEngine to confirm that a
 * 401 on the login endpoint surfaces an error instead of clearing the session.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FeedViewModelUnauthorizedTest {

    private fun makeVm(
        settings: InMemorySettings = InMemorySettings().apply {
            putBoolean("session_active", true)
            // A non-blank username so onApiError surfaces it (blank usernames are
            // mapped to null so the modal never renders a blank identity panel).
            putString("session_username", "bob")
        },
    ): Pair<FeedViewModel, SessionManager> {
        val sessionManager = SessionManager(settings)
        val authEngine = MockEngine { respond("", HttpStatusCode.OK) }
        val apiSettings: Settings = InMemorySettings()
        val vm = FeedViewModel(
            repository = noopRepo(),
            authApi = AuthApi(HttpClient(authEngine)),
            sessionManager = sessionManager,
            clearCookies = {},
            serverUrlStore = ServerUrlStore(apiSettings),
            userPrefs = UserPrefs(apiSettings),
        )
        return vm to sessionManager
    }

    // Build a real ClientRequestException via a direct (non-launch) MockEngine call in runTest.
    // `expectSuccess = false` lets us capture the response without Ktor throwing first.
    private suspend fun buildException(status: HttpStatusCode): ClientRequestException {
        val engine = MockEngine { respond("", status) }
        val client = HttpClient(engine) { expectSuccess = false }
        val response = client.get("http://test/")
        return ClientRequestException(response, "")
    }

    // -------------------------------------------------------------------------
    // onApiError — direct call tests
    // -------------------------------------------------------------------------

    @Test
    fun onApiErrorWith401SetsSessionExpiredUsername() = runTest {
        val (vm, session) = makeVm()
        assertTrue(session.isLoggedIn.value, "precondition: session starts active")
        val wasUnauthorized = vm.onApiError(buildException(HttpStatusCode.Unauthorized))
        assertTrue(wasUnauthorized, "onApiError should return true for ClientRequestException(401)")
        // Session is NOT cleared immediately — the modal confirms the action first.
        assertTrue(session.isLoggedIn.value, "session must stay active until user acknowledges modal")
        assertNotNull(vm.sessionExpiredUsername.value, "sessionExpiredUsername must be non-null on 401")
        vm.close()
    }

    @Test
    fun onApiErrorWith401BlankUsernameMapsToNull() = runTest {
        // F11: a blank stored username must NOT surface as a blank identity panel —
        // it is mapped to null so the SESSION EXPIRED modal hides the panel entirely.
        val settings = InMemorySettings().apply {
            putBoolean("session_active", true)
            putString("session_username", "")
        }
        val (vm, _) = makeVm(settings)
        vm.onApiError(buildException(HttpStatusCode.Unauthorized))
        assertNull(vm.sessionExpiredUsername.value, "blank username must map to null on 401")
        vm.close()
    }

    @Test
    fun onApiErrorWith401DoesNotSetErrorState() = runTest {
        // When 401 is detected the modal appears; the ViewModel must NOT also set an inline error.
        val (vm, _) = makeVm()
        vm.onApiError(buildException(HttpStatusCode.Unauthorized))
        assertTrue(vm.uiState.value is UiState.Idle, "uiState must remain Idle on 401")
        vm.close()
    }

    @Test
    fun acknowledgeSessionExpired_forgetFalse_clearsSessionAndSetsPrefillUsername() = runTest {
        val settings = InMemorySettings().apply {
            putBoolean("session_active", true)
            putString("session_username", "alice")
        }
        val sessionManager = SessionManager(settings)
        val vm = FeedViewModel(
            repository = noopRepo(),
            authApi = AuthApi(HttpClient(MockEngine { respond("", HttpStatusCode.OK) })),
            sessionManager = sessionManager,
            clearCookies = {},
            serverUrlStore = ServerUrlStore(InMemorySettings()),
            userPrefs = UserPrefs(InMemorySettings()),
            coroutineScope = CoroutineScope(coroutineContext + Job()),
        )
        vm.onApiError(buildException(HttpStatusCode.Unauthorized))
        assertEquals("alice", vm.sessionExpiredUsername.value)

        vm.acknowledgeSessionExpired(forgetDevice = false)
        testScheduler.advanceUntilIdle()

        assertFalse(sessionManager.isLoggedIn.value, "session must be cleared after acknowledgment")
        assertNull(vm.sessionExpiredUsername.value, "sessionExpiredUsername cleared after acknowledgment")
        assertEquals("alice", vm.prefillUsername.value, "prefill username set for Sign in again")
        vm.close()
    }

    @Test
    fun acknowledgeSessionExpired_forgetTrue_clearsSessionAndClearsCookies() = runTest {
        var cookiesCleared = false
        val settings = InMemorySettings().apply { putBoolean("session_active", true) }
        val sessionManager = SessionManager(settings)
        val vm = FeedViewModel(
            repository = noopRepo(),
            authApi = AuthApi(HttpClient(MockEngine { respond("", HttpStatusCode.OK) })),
            sessionManager = sessionManager,
            clearCookies = { cookiesCleared = true },
            serverUrlStore = ServerUrlStore(InMemorySettings()),
            userPrefs = UserPrefs(InMemorySettings()),
            coroutineScope = CoroutineScope(coroutineContext + Job()),
        )
        vm.onApiError(buildException(HttpStatusCode.Unauthorized))
        vm.acknowledgeSessionExpired(forgetDevice = true)
        testScheduler.advanceUntilIdle()

        assertFalse(sessionManager.isLoggedIn.value, "session must be cleared")
        assertTrue(cookiesCleared, "clearCookies must be called on forgetDevice=true")
        assertNull(vm.prefillUsername.value, "prefill username must NOT be set on forgetDevice=true")
        vm.close()
    }

    @Test
    fun onApiErrorWithPlainExceptionDoesNotClearSession() = runTest {
        val (vm, session) = makeVm()
        val wasUnauthorized = vm.onApiError(RuntimeException("network error"))
        assertFalse(wasUnauthorized, "onApiError should return false for non-HTTP exception")
        assertTrue(session.isLoggedIn.value, "session must not be cleared for non-HTTP error")
        vm.close()
    }

    // -------------------------------------------------------------------------
    // loginWith401 — end-to-end via MockEngine: login() MUST NOT clear session
    // -------------------------------------------------------------------------

    @Test
    fun loginWith401SetsLoginErrorInsteadOfClearingSession() = runTest {
        // Wrong credentials return 401 on the login endpoint. This must surface an error
        // message but NOT call sessionManager.setLoggedIn(false) — that would cause an
        // infinite redirect loop if the user is already on the login screen.
        val sessionManager = SessionManager(InMemorySettings())
        val unauthorizedEngine = MockEngine { respond("Unauthorized", HttpStatusCode.Unauthorized) }
        val authClient = HttpClient(unauthorizedEngine) { expectSuccess = true }
        val apiSettings: Settings = InMemorySettings()
        val vm = FeedViewModel(
            repository = noopRepo(),
            authApi = AuthApi(authClient),
            sessionManager = sessionManager,
            clearCookies = {},
            serverUrlStore = ServerUrlStore(apiSettings),
            userPrefs = UserPrefs(apiSettings),
            coroutineScope = CoroutineScope(coroutineContext + Job()),
        )
        vm.login("user", "wrongpass")
        testScheduler.advanceUntilIdle()
        // isLoggedIn must remain false (login was never successful)
        assertFalse(sessionManager.isLoggedIn.value, "login() 401 must not change session state")
        // The error message must be set so the user sees why login failed
        assertNotNull(vm.loginError.value, "login() 401 must surface a login error")
        vm.close()
    }
}
