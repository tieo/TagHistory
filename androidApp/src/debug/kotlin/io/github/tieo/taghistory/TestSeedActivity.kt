package io.github.tieo.taghistory

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import io.github.tieo.taghistory.host.seedTestData

/**
 * Debug-only activity. Triggered via deep link `taghistory://test/seed` or
 * ADB: `adb shell am start -n io.github.tieo.taghistory/.TestSeedActivity`
 *
 * Seeds fake auth + beacons + location reports (idempotent), then hands
 * off to MainActivity so Maestro lands on the map screen.
 */
class TestSeedActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        seedTestData(applicationContext)
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        )
        finish()
    }
}
