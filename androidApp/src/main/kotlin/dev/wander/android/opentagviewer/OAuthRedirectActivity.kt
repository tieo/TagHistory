package io.github.tieo.taghistory

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Receives the sync server sign-in answer the browser hands back through
 * the app's redirect scheme, passes it to the app-wide controller and
 * returns to the app where the user left it.
 */
class OAuthRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.let { (application as TagHistoryApp).host.serverSync.onRedirect(it.toString()) }
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
}
