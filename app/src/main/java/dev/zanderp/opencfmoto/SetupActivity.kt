// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import dev.zanderp.opencfmoto.aa.AaServerStarterService
import java.io.File

/**
 * One-time guided setup + persistent settings. Shows a live checklist of the prerequisites for
 * Android Auto projection (install, permissions, head-unit mode) and the display/battery options.
 * Each selector reflects the saved choice with a highlighted segment so the current setting is
 * always visible. Status is re-evaluated in [onResume] so returning from a permission prompt or the
 * Play Store re-ticks the steps.
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var step1Title: TextView
    private lateinit var step1Btn: MaterialButton
    private lateinit var step2Title: TextView
    private lateinit var step2Btn: MaterialButton
    private lateinit var qualityDesc: TextView
    private lateinit var fitDesc: TextView
    private lateinit var powerDesc: TextView
    private lateinit var resDesc: TextView
    private lateinit var themeDesc: TextView
    private lateinit var dblTapDesc: TextView
    private lateinit var holdsDesc: TextView
    private lateinit var holdDesc: TextView
    private lateinit var nonTouchDesc: TextView
    private lateinit var forceTouchDesc: TextView
    private lateinit var profileDesc: TextView
    private lateinit var btStatus: TextView
    private lateinit var resumeBtn: MaterialButton
    private lateinit var step3Title: TextView
    private lateinit var step3AutoSwitch: MaterialSwitch
    private lateinit var step3RunBtn: MaterialButton

    private var syncingSwitches = false
    private var awaitingAccessibility = false

    private val importSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        importSettingsFromUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        hideCardsTitled(
            findViewById(R.id.setup_root),
            setOf(
                getString(R.string.setup_bike_profile),
                getString(R.string.setup_wi_fi_logs),
                getString(R.string.setup_supported_bikes),
            ),
        )

        // Draw content clear of the status/navigation bars (edge-to-edge is enforced on newer Android).
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.setup_root)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, b.top, v.paddingRight, b.bottom)
            insets
        }

        step1Title = findViewById(R.id.step1_title)
        step1Btn = findViewById(R.id.step1_btn)
        step2Title = findViewById(R.id.step2_title)
        step2Btn = findViewById(R.id.step2_btn)
        qualityDesc = findViewById(R.id.quality_desc)
        fitDesc = findViewById(R.id.fit_desc)
        powerDesc = findViewById(R.id.power_desc)
        resDesc = findViewById(R.id.res_desc)
        themeDesc = findViewById(R.id.theme_desc)
        dblTapDesc = findViewById(R.id.dbltap_desc)
        holdsDesc = findViewById(R.id.holds_desc)
        holdDesc = findViewById(R.id.hold_desc)
        nonTouchDesc = findViewById(R.id.nontouch_desc)
        forceTouchDesc = findViewById(R.id.forcetouch_desc)
        profileDesc = findViewById(R.id.profile_desc)
        btStatus = findViewById(R.id.bt_status)
        resumeBtn = findViewById(R.id.resume_perm_btn)

        step1Btn.setOnClickListener { openAndroidAuto() }
        step2Btn.setOnClickListener { requestMissingPermissions() }
        findViewById<MaterialButton>(R.id.step2_settings_btn).setOnClickListener { openAppSettings() }
        findViewById<MaterialButton>(R.id.step3_btn).setOnClickListener { openAndroidAutoSettings() }
        step3Title = findViewById(R.id.step3_title)
        step3AutoSwitch = findViewById(R.id.step3_auto_switch)
        step3RunBtn = findViewById(R.id.step3_run_btn)
        step3AutoSwitch.setOnCheckedChangeListener { _, on ->
            if (syncingSwitches) return@setOnCheckedChangeListener
            if (on == AaServerStarterService.isEnabled(this)) return@setOnCheckedChangeListener
            awaitingAccessibility = on
            openAccessibilitySettings(
                if (on) getString(R.string.aa_starter_enable_hint) else getString(R.string.aa_starter_disable_hint),
            )
        }
        step3RunBtn.setOnClickListener { runAndroidAutoSetup() }

        findViewById<MaterialButton>(R.id.quality_smooth).setOnClickListener { setQuality(VideoQuality.SMOOTH) }
        findViewById<MaterialButton>(R.id.quality_balanced).setOnClickListener { setQuality(VideoQuality.BALANCED) }
        findViewById<MaterialButton>(R.id.quality_sharp).setOnClickListener { setQuality(VideoQuality.SHARP) }
        findViewById<MaterialButton>(R.id.fit_fill).setOnClickListener { setFit(ScreenFit.FILL) }
        findViewById<MaterialButton>(R.id.fit_fit).setOnClickListener { setFit(ScreenFit.FIT) }
        findViewById<MaterialButton>(R.id.fit_stretch).setOnClickListener { setFit(ScreenFit.STRETCH) }
        findViewById<MaterialButton>(R.id.power_auto).setOnClickListener { setPower(PowerMode.AUTO) }
        findViewById<MaterialButton>(R.id.power_smooth).setOnClickListener { setPower(PowerMode.SMOOTH) }
        findViewById<MaterialButton>(R.id.power_balanced).setOnClickListener { setPower(PowerMode.BALANCED) }
        findViewById<MaterialButton>(R.id.power_saver).setOnClickListener { setPower(PowerMode.SAVER) }
        findViewById<MaterialButton>(R.id.res_auto).setOnClickListener { setResolution(ResolutionMode.AUTO) }
        findViewById<MaterialButton>(R.id.res_land_sd).setOnClickListener { setResolution(ResolutionMode.LANDSCAPE_SD) }
        findViewById<MaterialButton>(R.id.res_land_hd).setOnClickListener { setResolution(ResolutionMode.LANDSCAPE_HD) }
        findViewById<MaterialButton>(R.id.res_port_sd).setOnClickListener { setResolution(ResolutionMode.PORTRAIT_SD) }
        findViewById<MaterialButton>(R.id.res_port_hd).setOnClickListener { setResolution(ResolutionMode.PORTRAIT_HD) }
        findViewById<MaterialButton>(R.id.theme_auto).setOnClickListener { setMapTheme(MapTheme.AUTO) }
        findViewById<MaterialButton>(R.id.theme_day).setOnClickListener { setMapTheme(MapTheme.DAY) }
        findViewById<MaterialButton>(R.id.theme_night).setOnClickListener { setMapTheme(MapTheme.NIGHT) }
        findViewById<MaterialButton>(R.id.dbltap_fast).setOnClickListener { setDoubleTap(DoubleTapDelay.FAST) }
        findViewById<MaterialButton>(R.id.dbltap_normal).setOnClickListener { setDoubleTap(DoubleTapDelay.NORMAL) }
        findViewById<MaterialButton>(R.id.dbltap_slow).setOnClickListener { setDoubleTap(DoubleTapDelay.SLOW) }
        findViewById<MaterialButton>(R.id.dbltap_very_slow).setOnClickListener {
            setDoubleTap(DoubleTapDelay.VERY_SLOW)
        }
        findViewById<MaterialButton>(R.id.hold_short).setOnClickListener { setLongPress(LongPressDelay.SHORT) }
        findViewById<MaterialButton>(R.id.hold_normal).setOnClickListener { setLongPress(LongPressDelay.NORMAL) }
        findViewById<MaterialButton>(R.id.hold_long).setOnClickListener { setLongPress(LongPressDelay.LONG) }
        findViewById<MaterialButton>(R.id.profile_auto).setOnClickListener { setProfileOverride(ProfileOverride.AUTO) }
        findViewById<MaterialButton>(R.id.profile_legacy).setOnClickListener { setProfileOverride(ProfileOverride.LEGACY) }
        findViewById<MaterialButton>(R.id.profile_nk800).setOnClickListener { setProfileOverride(ProfileOverride.NK800) }
        findViewById<MaterialButton>(R.id.profile_800mt).setOnClickListener { setProfileOverride(ProfileOverride.CFDL26_LAND) }
        findViewById<MaterialButton>(R.id.profile_1000mtx).setOnClickListener { setProfileOverride(ProfileOverride.CFDL26_PORT) }
        findViewById<MaterialButton>(R.id.profile_nk_adv).setOnClickListener { setProfileOverride(ProfileOverride.NK_ADV) }
        findViewById<MaterialButton>(R.id.profile_clc450).setOnClickListener { setProfileOverride(ProfileOverride.CLC450) }
        findViewById<MaterialButton>(R.id.btn_screen_margins).setOnClickListener { ScreenMarginsActivity.start(this) }
        findViewById<MaterialButton>(R.id.btn_custom_resolution).setOnClickListener { CustomResolutionActivity.start(this) }
        findViewById<MaterialButton>(R.id.transport_auto).setOnClickListener { setTransport(WifiTransport.AUTO) }
        findViewById<MaterialButton>(R.id.transport_ap).setOnClickListener { setTransport(WifiTransport.AP) }
        findViewById<MaterialButton>(R.id.transport_p2p).setOnClickListener { setTransport(WifiTransport.P2P) }
        findViewById<MaterialButton>(R.id.transport_existing).setOnClickListener {
            setTransport(WifiTransport.EXISTING)
        }
        findViewById<MaterialButton>(R.id.settings_share).setOnClickListener { shareSettingsJson() }
        findViewById<MaterialButton>(R.id.settings_import).setOnClickListener {
            importSettingsLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
        }
        findViewById<MaterialButton>(R.id.bt_settings_btn).setOnClickListener { BluetoothHelper.openBluetoothSettings(this) }

        bindSwitch(R.id.holds_switch, { ButtonTimingPrefs.holdsEnabled(this) }, ::setHoldsEnabled)
        bindSwitch(R.id.autoconnect_switch, { AppSettings.autoConnect(this) }, ::setAutoConnect)
        bindSwitch(R.id.recovery_switch, { AppSettings.autoRecovery(this) }, ::setAutoRecovery)
        bindSwitch(R.id.logtrips_switch, { AppSettings.logTrips(this) }, ::setLogTrips)
        bindSwitch(R.id.nontouch_switch, { AppSettings.forceNonTouch(this) }, ::setForceNonTouch)
        bindSwitch(R.id.forcetouch_switch, { AppSettings.forceTouch(this) }, ::setForceTouch)
        bindSwitch(R.id.secrets_switch, { AppSettings.includeSecretsInLogs(this) }, ::setSecrets)
        bindSwitch(R.id.telemetry_switch, { AppSettings.anonymousTelemetry(this) }, ::setTelemetry)
        resumeBtn.setOnClickListener { requestOverlayPermission() }

        findViewById<MaterialButton>(R.id.setup_done_btn).setOnClickListener {
            markSeen(this)
            finish()
        }
    }

    /** Write the portable settings JSON and open the system share sheet (Discord, Drive, …). */
    private fun shareSettingsJson() {
        try {
            val json = SettingsBackup.exportJson(this)
            val dir = File(cacheDir, "settings").apply { mkdirs() }
            val file = File(dir, SettingsBackup.suggestedFileName(this))
            file.writeText(json, Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val profile = ProfilePrefs.get(this).shortLabel
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_SUBJECT, "Open Kove bike tuning — $profile")
                putExtra(
                    Intent.EXTRA_TEXT,
                    "Open Kove bike tuning ($profile) — profile / resolution / margins / buttons " +
                        "(no passwords, no bike name/SSID).\n" +
                        "Import via Setup → Wi‑Fi & logs → Import…",
                )
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = android.content.ClipData.newUri(contentResolver, file.name, uri)
            }
            startActivity(Intent.createChooser(send, "Share settings JSON"))
        } catch (e: Exception) {
            Toast.makeText(this, "Share failed: $e", Toast.LENGTH_LONG).show()
        }
    }

    private fun importSettingsFromUri(uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            if (text.isNullOrBlank()) {
                Toast.makeText(this, "Empty file", Toast.LENGTH_SHORT).show()
                return
            }
            val onto = BikeMemory.lastBikeName(this) ?: "the selected bike"
            AlertDialog.Builder(this)
                .setTitle("Import settings?")
                .setMessage(
                    "Replace bike tuning for $onto — profile, resolution, fit, power, margins, " +
                        "handlebar buttons, Control AA, non-touch, Wi‑Fi transport?\n\n" +
                        "Personal prefs (map theme, saved places, auto-connect, …) are left alone. " +
                        "Wi‑Fi passwords are never imported."
                )
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Import") { _, _ ->
                    val result = SettingsBackup.importJson(this, text)
                    refreshOptions()
                    Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                }
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "Import failed: $e", Toast.LENGTH_LONG).show()
        }
    }

    private fun setQuality(q: VideoQuality) {
        VideoPrefs.set(this, q)
        refreshOptions()
        toast(getString(R.string.setup_toast_video_quality, getString(q.labelRes)))
    }

    private fun setFit(f: ScreenFit) {
        VideoPrefs.setFit(this, f)
        refreshOptions()
        toast(getString(R.string.setup_toast_screen_fit, getString(f.labelRes)))
    }

    private fun setPower(m: PowerMode) {
        VideoPrefs.setPower(this, m)
        refreshOptions()
        toast(getString(R.string.setup_toast_power_mode, getString(m.labelRes)))
    }

    private fun setResolution(m: ResolutionMode) {
        VideoPrefs.setResolution(this, m)
        refreshOptions()
        toast(getString(R.string.setup_toast_resolution, getString(m.labelRes)))
    }

    /** Map day/night applies live (no reconnect needed) — push it to any running AA session. */
    private fun setMapTheme(theme: MapTheme) {
        NightPrefs.setTheme(this, theme)
        AaVideoBridge.nightSink?.invoke(NightPrefs.isNightNow(this))
        refreshOptions()
        Toast.makeText(this, getString(R.string.setup_toast_map_theme, getString(theme.labelRes)), Toast.LENGTH_SHORT).show()
    }

    /** Button timing applies live — the next press uses the new window. */
    private fun setDoubleTap(delay: DoubleTapDelay) {
        ButtonTimingPrefs.setDoubleTap(this, delay)
        refreshOptions()
        Toast.makeText(this, getString(R.string.setup_toast_dbl_tap, getString(delay.labelRes)), Toast.LENGTH_SHORT).show()
    }

    private fun setLongPress(delay: LongPressDelay) {
        ButtonTimingPrefs.setLongPress(this, delay)
        refreshOptions()
        Toast.makeText(this, getString(R.string.setup_toast_hold, getString(delay.labelRes)), Toast.LENGTH_SHORT).show()
    }

    private fun bindSwitch(id: Int, read: () -> Boolean, write: (Boolean) -> Unit) {
        findViewById<MaterialSwitch>(id).setOnCheckedChangeListener { _, on ->
            if (syncingSwitches || on == read()) return@setOnCheckedChangeListener
            write(on)
        }
    }

    private fun setHoldsEnabled(on: Boolean) {
        ButtonTimingPrefs.setHoldsEnabled(this, on)
        refreshOptions()
    }

    private fun setAutoConnect(on: Boolean) {
        AppSettings.setAutoConnect(this, on)
        refreshOptions()
    }

    private fun setAutoRecovery(on: Boolean) {
        AppSettings.setAutoRecovery(this, on)
        refreshOptions()
    }

    private fun setLogTrips(on: Boolean) {
        AppSettings.setLogTrips(this, on)
        refreshOptions()
    }

    private fun setForceNonTouch(on: Boolean) {
        AppSettings.setForceNonTouch(this, on)
        refreshOptions()
        toast(getString(R.string.setup_disable_touchscreen))
    }

    private fun setForceTouch(on: Boolean) {
        AppSettings.setForceTouch(this, on)
        refreshOptions()
        toast(getString(R.string.setup_force_touchscreen))
    }

    private fun setProfileOverride(ov: ProfileOverride) {
        ProfilePrefs.set(this, ov)
        refreshOptions()
        toast("Bike profile: ${ov.shortLabel}")
    }

    private fun setTransport(t: WifiTransport) {
        AppSettings.setTransport(this, t)
        refreshOptions()
        toast(getString(R.string.setup_toast_transport, t.label))
    }

    private fun setSecrets(on: Boolean) {
        AppSettings.setIncludeSecretsInLogs(this, on)
        refreshOptions()
        if (on) Toast.makeText(this, getString(R.string.setup_secrets_on_desc), Toast.LENGTH_SHORT).show()
    }

    private fun setTelemetry(on: Boolean) {
        AppSettings.setAnonymousTelemetry(this, on)
        refreshOptions()
        if (on) AnonymousTelemetry.onAppStart(this)
        Toast.makeText(
            this,
            if (on) getString(R.string.setup_anonymous_telemetry_on)
            else getString(R.string.setup_anonymous_telemetry_off),
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun toast(msg: String) =
        Toast.makeText(this, getString(R.string.setup_applies_next_connect, msg), Toast.LENGTH_SHORT).show()

    /** Update every selector's description + highlight the active segment. */
    private fun refreshOptions() {
        val quality = VideoPrefs.get(this)
        val fit = VideoPrefs.fit(this)
        val power = VideoPrefs.power(this)
        val res = VideoPrefs.resolution(this)
        val theme = NightPrefs.theme(this)
        val dbl = ButtonTimingPrefs.doubleTap(this)
        val holdsOn = ButtonTimingPrefs.holdsEnabled(this)
        val hold = ButtonTimingPrefs.longPress(this)
        qualityDesc.text = getString(quality.labelRes)
        fitDesc.text = getString(fit.labelRes)
        powerDesc.text = getString(power.labelRes)
        resDesc.text = getString(res.labelRes)
        themeDesc.text = getString(theme.labelRes)
        dblTapDesc.text = getString(dbl.labelRes)
        holdsDesc.text = if (holdsOn) {
            getString(R.string.setup_on_press_and_hold_can_fire_hold_gestures)
        } else {
            getString(R.string.setup_holds_off_desc)
        }
        holdDesc.text = if (holdsOn) getString(hold.labelRes) else getString(R.string.pref_hold_ignored)
        nonTouchDesc.text = if (AppSettings.forceNonTouch(this))
            getString(R.string.setup_nontouch_on_desc)
        else
            getString(R.string.setup_off_use_the_bike_profile_touch_dashes_stay_touch)
        forceTouchDesc.text = if (AppSettings.forceTouch(this))
            getString(R.string.setup_forcetouch_on_desc)
        else
            getString(R.string.setup_off_use_the_bike_profile_1000_mt_x_stays_focus_k)
        val pov = ProfilePrefs.get(this)
        profileDesc.text = "${pov.shortLabel} — ${getString(pov.detailRes)}"

        highlight(quality,
            R.id.quality_smooth to VideoQuality.SMOOTH,
            R.id.quality_balanced to VideoQuality.BALANCED,
            R.id.quality_sharp to VideoQuality.SHARP)
        highlight(fit,
            R.id.fit_fill to ScreenFit.FILL,
            R.id.fit_fit to ScreenFit.FIT,
            R.id.fit_stretch to ScreenFit.STRETCH)
        highlight(power,
            R.id.power_auto to PowerMode.AUTO,
            R.id.power_smooth to PowerMode.SMOOTH,
            R.id.power_balanced to PowerMode.BALANCED,
            R.id.power_saver to PowerMode.SAVER)
        highlight(res,
            R.id.res_auto to ResolutionMode.AUTO,
            R.id.res_land_sd to ResolutionMode.LANDSCAPE_SD,
            R.id.res_land_hd to ResolutionMode.LANDSCAPE_HD,
            R.id.res_port_sd to ResolutionMode.PORTRAIT_SD,
            R.id.res_port_hd to ResolutionMode.PORTRAIT_HD)
        highlight(theme,
            R.id.theme_auto to MapTheme.AUTO,
            R.id.theme_day to MapTheme.DAY,
            R.id.theme_night to MapTheme.NIGHT)
        highlight(dbl,
            R.id.dbltap_fast to DoubleTapDelay.FAST,
            R.id.dbltap_normal to DoubleTapDelay.NORMAL,
            R.id.dbltap_slow to DoubleTapDelay.SLOW,
            R.id.dbltap_very_slow to DoubleTapDelay.VERY_SLOW)
        highlight(hold,
            R.id.hold_short to LongPressDelay.SHORT,
            R.id.hold_normal to LongPressDelay.NORMAL,
            R.id.hold_long to LongPressDelay.LONG)

        highlight(ProfilePrefs.get(this),
            R.id.profile_auto to ProfileOverride.AUTO,
            R.id.profile_legacy to ProfileOverride.LEGACY,
            R.id.profile_nk800 to ProfileOverride.NK800,
            R.id.profile_800mt to ProfileOverride.CFDL26_LAND,
            R.id.profile_1000mtx to ProfileOverride.CFDL26_PORT,
            R.id.profile_nk_adv to ProfileOverride.NK_ADV,
            R.id.profile_clc450 to ProfileOverride.CLC450)
        val transport = AppSettings.transport(this)
        findViewById<android.widget.TextView>(R.id.transport_desc).text = transport.label
        highlight(transport,
            R.id.transport_auto to WifiTransport.AUTO,
            R.id.transport_ap to WifiTransport.AP,
            R.id.transport_p2p to WifiTransport.P2P,
            R.id.transport_existing to WifiTransport.EXISTING)
        val secrets = AppSettings.includeSecretsInLogs(this)
        findViewById<android.widget.TextView>(R.id.secrets_desc).text =
            if (secrets) getString(R.string.setup_secrets_on_desc)
            else getString(R.string.setup_off_passwords_and_serials_are_redacted_recommend)
        val telemetry = AppSettings.anonymousTelemetry(this)
        findViewById<android.widget.TextView>(R.id.telemetry_desc).text =
            getString(R.string.setup_anonymous_telemetry_desc)
        findViewById<android.widget.TextView>(R.id.telemetry_id).text =
            getString(R.string.setup_anonymous_id, AnonymousTelemetry.anonUuidForDisplay(this))

        syncingSwitches = true
        try {
            setSwitch(R.id.holds_switch, holdsOn)
            setSwitch(R.id.autoconnect_switch, AppSettings.autoConnect(this))
            setSwitch(R.id.recovery_switch, AppSettings.autoRecovery(this))
            setSwitch(R.id.logtrips_switch, AppSettings.logTrips(this))
            setSwitch(R.id.nontouch_switch, AppSettings.forceNonTouch(this))
            setSwitch(R.id.forcetouch_switch, AppSettings.forceTouch(this))
            setSwitch(R.id.secrets_switch, secrets)
            setSwitch(R.id.telemetry_switch, telemetry)
        } finally {
            syncingSwitches = false
        }
    }

    private fun setSwitch(id: Int, on: Boolean) {
        val sw = findViewById<MaterialSwitch>(id)
        if (sw.isChecked != on) sw.isChecked = on
    }

    /** Refresh the Bluetooth pairing status line shown in the helper card. */
    private fun refreshBluetooth() {
        btStatus.text = BluetoothHelper.status(this).describe(this)
    }

    /** Paint the segment matching [selected] in brand color; the rest stay neutral tonal. */
    private fun <T> highlight(selected: T, vararg pairs: Pair<Int, T>) {
        val onColor = ContextCompat.getColor(this, R.color.brand_orange)
        val onText = ContextCompat.getColor(this, R.color.on_brand)
        val offColor = ContextCompat.getColor(this, R.color.surface_high)
        val offText = ContextCompat.getColor(this, R.color.text_primary)
        for ((id, value) in pairs) {
            val btn = findViewById<MaterialButton>(id)
            val on = value == selected
            btn.backgroundTintList = ColorStateList.valueOf(if (on) onColor else offColor)
            btn.setTextColor(if (on) onText else offText)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        refreshOptions()
        refreshBluetooth()
        if (awaitingAccessibility && AaServerStarterService.isEnabled(this)) {
            awaitingAccessibility = false
            runAndroidAutoSetup()
        }
    }

    private fun openAccessibilitySettings(hint: String) {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, hint, Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.main_settings_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun runAndroidAutoSetup() {
        if (!SetupHelper.isAndroidAutoInstalled(this)) {
            openAndroidAuto()
            return
        }
        if (!AaServerStarterService.isEnabled(this)) {
            awaitingAccessibility = true
            openAccessibilitySettings(getString(R.string.aa_starter_enable_hint))
            return
        }
        AaServerStarterService.request()
        Toast.makeText(this, getString(R.string.aa_starter_running), Toast.LENGTH_LONG).show()
        openAndroidAutoSettings()
    }

    private fun refresh() {
        val aaOk = SetupHelper.isAndroidAutoInstalled(this)
        step1Title.text = tick(aaOk) + " " + getString(R.string.setup_1_android_auto)
        step1Btn.text = if (aaOk) getString(R.string.setup_open_android_auto) else getString(R.string.setup_install_android_auto)

        val permsOk = SetupHelper.permissionsGranted(this)
        step2Title.text = tick(permsOk) + " " + getString(R.string.setup_2_permissions)
        step2Btn.text = if (permsOk) getString(R.string.setup_all_granted) else getString(R.string.setup_grant_permissions)
        step2Btn.isEnabled = !permsOk

        val autoOk = AaServerStarterService.isEnabled(this)
        step3Title.text = tick(autoOk) + " " + getString(R.string.setup_3_head_unit_mode_in_android_auto)
        syncingSwitches = true
        try {
            if (step3AutoSwitch.isChecked != autoOk) step3AutoSwitch.isChecked = autoOk
        } finally {
            syncingSwitches = false
        }
        step3RunBtn.text = getString(
            if (autoOk) R.string.aa_starter_run_now else R.string.aa_starter_button,
        )

        val resumeOk = SetupHelper.canAutoResume(this)
        resumeBtn.text = if (resumeOk) getString(R.string.setup_seamless_resume_enabled)
            else getString(R.string.setup_enable_seamless_resume)
        resumeBtn.isEnabled = !resumeOk
    }

    /** Deep-link to the "Display over other apps" screen for this app (overlay = seamless auto-resume). */
    private fun requestOverlayPermission() {
        if (SetupHelper.canAutoResume(this)) return
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.fromParts("package", packageName, null)))
            Toast.makeText(this, getString(R.string.setup_overlay_for_seamless_resume),
                Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            } catch (_: Exception) {
                Toast.makeText(this, getString(R.string.setup_overlay_permission_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun tick(ok: Boolean): String = if (ok) "\u2713" else "\u2022"

    private fun requestMissingPermissions() {
        val missing = SetupHelper.missingPermissions(this)
        if (missing.isEmpty()) { refresh(); return }
        ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
        // If anything is still missing after the prompt, the user likely hit "Don't allow" — point
        // them at the system settings where it can still be granted.
        if (!SetupHelper.permissionsGranted(this)) {
            Toast.makeText(this,
                getString(R.string.setup_some_permissions_still_off),
                Toast.LENGTH_LONG).show()
        }
    }

    private fun openAppSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)))
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.main_settings_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAndroidAuto() {
        if (SetupHelper.isAndroidAutoInstalled(this)) {
            openAndroidAutoSettings()
            return
        }
        val market = Intent(Intent.ACTION_VIEW,
            Uri.parse("market://details?id=${SetupHelper.GEARHEAD_PACKAGE}"))
        try {
            startActivity(market)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=${SetupHelper.GEARHEAD_PACKAGE}")))
            } catch (_: Exception) {
                Toast.makeText(this, getString(R.string.setup_play_store_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Sections that only mattered for other bikes. Their views stay bound, just out of sight. */
    private fun hideCardsTitled(view: android.view.View, titles: Set<String>) {
        if (view is com.google.android.material.card.MaterialCardView) {
            if (firstText(view) in titles) view.visibility = android.view.View.GONE
            return
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) hideCardsTitled(view.getChildAt(i), titles)
        }
    }

    private fun firstText(view: android.view.View): String? {
        if (view is android.widget.TextView) return view.text?.toString()
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) firstText(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun openAndroidAutoSettings() {
        try {
            startActivity(Intent("android.settings.ANDROID_AUTO_SETTINGS")
                .setPackage(SetupHelper.GEARHEAD_PACKAGE))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_MAIN).setClassName(
                    SetupHelper.GEARHEAD_PACKAGE,
                    "com.google.android.projection.gearhead.companion.settings.DefaultSettingsActivity"))
            } catch (_: Exception) {
                Toast.makeText(this, getString(R.string.setup_android_auto_settings_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        private const val REQ_PERMS = 42
        private const val PREFS = "opencfmoto_bike"
        private const val KEY_SEEN = "setup_seen"

        fun hasSeen(ctx: android.content.Context): Boolean =
            ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_SEEN, false)

        fun markSeen(ctx: android.content.Context) {
            ctx.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_SEEN, true).apply()
        }

        fun start(ctx: android.content.Context) {
            ctx.startActivity(Intent(ctx, SetupActivity::class.java))
        }
    }
}
