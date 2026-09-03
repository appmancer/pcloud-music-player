package com.sjpickard.pcloudmusic.cloud

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.sjpickard.pcloudmusic.BuildConfig
import java.net.URLDecoder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * pCloud OAuth via the plain browser "token flow" (response_type=token) -
 * see https://docs.pcloud.com/methods/oauth_2.0/authorize.html, which
 * describes this as "appropriate for pure client-side apps - such for
 * mobile devices". No client secret is involved (that's only needed for the
 * server-side "code" flow), and the returned bearer token doesn't expire
 * the way OneDrive/MSAL's do, so - unlike OneDriveAuthManager - there's no
 * silent-refresh dance here: once signed in, [accessToken] is good until
 * the user explicitly signs out or revokes the app.
 *
 * Deliberately doesn't depend on com.pcloud.sdk:android - its Android-
 * specific AuthorizationActivity API isn't documented well enough to trust
 * blind, and the plain REST token flow is simple enough (and well
 * documented) to implement directly here, matching how PCloudApiClient
 * talks to the REST endpoints directly rather than through the SDK.
 */
class PCloudAuthManager(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("pcloud_auth", Context.MODE_PRIVATE)

    data class AuthState(val accessToken: String?, val apiHost: String?, val userId: String?) {
        val isSignedIn: Boolean get() = accessToken != null && apiHost != null
    }

    private val _authState = MutableStateFlow(loadStoredState())
    val authState: StateFlow<AuthState> = _authState

    private fun loadStoredState(): AuthState = AuthState(
        accessToken = prefs.getString(KEY_TOKEN, null),
        apiHost = prefs.getString(KEY_HOST, null),
        userId = prefs.getString(KEY_UID, null),
    )

    /** Launches the system browser for the user to approve access. The
     * result comes back as a deep link to PCloudRedirectActivity (see
     * AndroidManifest.xml's pcloudmusic://oauth intent-filter), not through
     * this call directly - call [completeSignIn] from there. */
    fun buildAuthorizationIntent(): Intent {
        val uri = Uri.parse("https://my.pcloud.com/oauth2/authorize").buildUpon()
            .appendQueryParameter("client_id", BuildConfig.PCLOUD_CLIENT_ID)
            .appendQueryParameter("response_type", "token")
            .appendQueryParameter("redirect_uri", BuildConfig.PCLOUD_REDIRECT_URI)
            .build()
        return Intent(Intent.ACTION_VIEW, uri)
    }

    /** [redirectUri] is the full pcloudmusic://oauth#access_token=...&hostname=...
     * URI PCloudRedirectActivity received. Token-flow params come back in the
     * URI *fragment*, not the query string. Returns false (and leaves any
     * previous sign-in state untouched) if the redirect didn't actually carry
     * a token - e.g. the user declined the authorization request. */
    fun completeSignIn(redirectUri: Uri): Boolean {
        val params = parseFragmentParams(redirectUri.fragment ?: return false)
        val token = params["access_token"] ?: return false
        val host = params["hostname"] ?: return false
        val uid = params["uid"]
        prefs.edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_HOST, host)
            .putString(KEY_UID, uid)
            .apply()
        _authState.value = AuthState(token, host, uid)
        return true
    }

    fun signOut() {
        prefs.edit().clear().apply()
        _authState.value = AuthState(null, null, null)
    }

    /** e.g. "https://api.pcloud.com" (US) or "https://eapi.pcloud.com"
     * (Europe) - the data-center-local host returned at sign-in time; every
     * subsequent call for this account must go to the same one. */
    fun apiBaseUrl(): String? = _authState.value.apiHost?.let { "https://$it" }

    fun accessToken(): String? = _authState.value.accessToken

    private fun parseFragmentParams(fragment: String): Map<String, String> =
        fragment.split("&").mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx < 0) return@mapNotNull null
            val key = pair.substring(0, idx)
            val rawValue = pair.substring(idx + 1)
            val value = try { URLDecoder.decode(rawValue, "UTF-8") } catch (e: Exception) { rawValue }
            key to value
        }.toMap()

    private companion object {
        const val KEY_TOKEN = "access_token"
        const val KEY_HOST = "api_host"
        const val KEY_UID = "uid"
    }
}
