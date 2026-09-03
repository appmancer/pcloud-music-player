package com.sjpickard.pcloudmusic.cloud

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.sjpickard.pcloudmusic.PcloudMusicApplication
import com.sjpickard.pcloudmusic.ui.MainActivity

private const val TAG = "PCloudRedirectActivity"

/** Bare catch point for pCloud's OAuth browser redirect (pcloudmusic://oauth#...
 * - see AndroidManifest.xml and PCloudAuthManager.buildAuthorizationIntent).
 * Hands the token off to the app's single PCloudAuthManager instance, then
 * brings MainActivity back to the foreground - this activity has no UI of
 * its own, it only ever appears for the instant it takes to do that. */
class PCloudRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as PcloudMusicApplication
        val uri = intent?.data
        val success = uri?.let { app.pCloudAuthManager.completeSignIn(it) } ?: false
        Log.d(TAG, "completeSignIn success=$success uri=$uri")

        startActivity(
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        )
        finish()
    }
}
