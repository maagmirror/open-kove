// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of Open Kove. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import dev.zanderp.opencfmoto.aa.AaServerStarterService

/**
 * First-run checklist: Android Auto, permissions, automatic head unit server, then scan the bike.
 * Everything else lives in [SetupActivity]. Each step re-checks itself in [onResume].
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var aaTitle: TextView
    private lateinit var aaBtn: MaterialButton
    private lateinit var permsTitle: TextView
    private lateinit var permsBtn: MaterialButton
    private lateinit var permsSettingsBtn: MaterialButton
    private lateinit var autoTitle: TextView
    private lateinit var autoSwitch: MaterialSwitch
    private lateinit var autoBtn: MaterialButton
    private lateinit var bikeTitle: TextView
    private lateinit var scanBtn: MaterialButton

    private var syncing = false
    private var awaitingAccessibility = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.onboarding_root)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, b.top, v.paddingRight, b.bottom)
            insets
        }

        aaTitle = findViewById(R.id.ob_aa_title)
        aaBtn = findViewById(R.id.ob_aa_btn)
        permsTitle = findViewById(R.id.ob_perms_title)
        permsBtn = findViewById(R.id.ob_perms_btn)
        permsSettingsBtn = findViewById(R.id.ob_perms_settings_btn)
        autoTitle = findViewById(R.id.ob_auto_title)
        autoSwitch = findViewById(R.id.ob_auto_switch)
        autoBtn = findViewById(R.id.ob_auto_btn)
        bikeTitle = findViewById(R.id.ob_bike_title)
        scanBtn = findViewById(R.id.ob_scan_btn)

        aaBtn.setOnClickListener { SetupHelper.openAndroidAutoOrStore(this) }
        permsBtn.setOnClickListener {
            val missing = SetupHelper.missingPermissions(this)
            if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_PERMS)
        }
        permsSettingsBtn.setOnClickListener { openAppInfo() }
        autoSwitch.setOnCheckedChangeListener { _, on ->
            if (syncing) return@setOnCheckedChangeListener
            if (on == AaServerStarterService.isEnabled(this)) return@setOnCheckedChangeListener
            awaitingAccessibility = on
            openAccessibilitySettings(
                if (on) getString(R.string.aa_starter_enable_hint) else getString(R.string.aa_starter_disable_hint),
            )
        }
        autoBtn.setOnClickListener { runAndroidAutoSetup() }

        val xiaomi = Build.MANUFACTURER.lowercase().let { it.contains("xiaomi") || it.contains("poco") || it.contains("redmi") }
        findViewById<View>(R.id.ob_xiaomi_card).visibility = if (xiaomi) View.VISIBLE else View.GONE
        findViewById<MaterialButton>(R.id.ob_xiaomi_btn).setOnClickListener { openAppInfo() }

        scanBtn.setOnClickListener {
            SetupActivity.markSeen(this)
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MainActivity.EXTRA_START_SCAN, !BikeMemory.hasSaved(this)),
            )
            finish()
        }
        findViewById<MaterialButton>(R.id.ob_done_btn).setOnClickListener {
            SetupActivity.markSeen(this)
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        if (awaitingAccessibility && AaServerStarterService.isEnabled(this)) {
            awaitingAccessibility = false
            runAndroidAutoSetup()
        }
    }

    private fun refresh() {
        val aaOk = SetupHelper.isAndroidAutoInstalled(this)
        aaTitle.text = tick(aaOk) + getString(R.string.onboarding_aa_title)
        aaBtn.text = getString(if (aaOk) R.string.setup_open_android_auto else R.string.setup_install_android_auto)

        val permsOk = SetupHelper.permissionsGranted(this)
        permsTitle.text = tick(permsOk) + getString(R.string.onboarding_perms_title)
        permsBtn.text = getString(if (permsOk) R.string.setup_all_granted else R.string.setup_grant_permissions)
        permsBtn.isEnabled = !permsOk

        val autoOk = AaServerStarterService.isEnabled(this)
        autoTitle.text = tick(autoOk) + getString(R.string.onboarding_auto_title)
        syncing = true
        try {
            if (autoSwitch.isChecked != autoOk) autoSwitch.isChecked = autoOk
        } finally {
            syncing = false
        }
        autoBtn.visibility = if (autoOk) View.VISIBLE else View.GONE

        val bikeOk = BikeMemory.hasSaved(this)
        bikeTitle.text = tick(bikeOk) + getString(R.string.onboarding_bike_title)
        scanBtn.text = getString(if (bikeOk) R.string.about_done else R.string.onboarding_scan_now)
    }

    private fun tick(ok: Boolean): String = if (ok) "✓ " else ""

    private fun runAndroidAutoSetup() {
        if (!SetupHelper.isAndroidAutoInstalled(this)) {
            SetupHelper.openAndroidAutoOrStore(this)
            return
        }
        if (!AaServerStarterService.isEnabled(this)) {
            awaitingAccessibility = true
            openAccessibilitySettings(getString(R.string.aa_starter_enable_hint))
            return
        }
        AaServerStarterService.request()
        Toast.makeText(this, getString(R.string.aa_starter_running), Toast.LENGTH_LONG).show()
        SetupHelper.openAndroidAutoSettings(this)
    }

    private fun openAccessibilitySettings(hint: String) {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, hint, Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.main_settings_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAppInfo() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.main_settings_failed), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
        if (!SetupHelper.permissionsGranted(this)) {
            permsSettingsBtn.visibility = View.VISIBLE
            Toast.makeText(this, getString(R.string.setup_some_permissions_still_off), Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val REQ_PERMS = 43

        /** Steps still missing before a first connection can work. */
        fun missingSteps(ctx: Context): Int {
            var n = 0
            if (!SetupHelper.isAndroidAutoInstalled(ctx)) n++
            if (!SetupHelper.permissionsGranted(ctx)) n++
            if (!AaServerStarterService.isEnabled(ctx)) n++
            if (!BikeMemory.hasSaved(ctx)) n++
            return n
        }

        fun start(ctx: Context) {
            ctx.startActivity(Intent(ctx, OnboardingActivity::class.java))
        }
    }
}
