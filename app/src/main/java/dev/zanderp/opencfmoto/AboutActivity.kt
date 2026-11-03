// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton

/** Copyright, license, credits, and project links. */
class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_about)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.about_root)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        findViewById<TextView>(R.id.about_version).text =
            "Version ${BuildConfig.VERSION_NAME}  ·  build ${BuildConfig.VERSION_CODE}"

        findViewById<MaterialButton>(R.id.btn_about_website).setOnClickListener {
            openUrl(URL_WEBSITE)
        }
        findViewById<MaterialButton>(R.id.btn_about_github).setOnClickListener {
            openUrl(URL_GITHUB)
        }
        findViewById<MaterialButton>(R.id.btn_about_discord).setOnClickListener {
            openUrl(URL_DISCORD)
        }
        findViewById<MaterialButton>(R.id.btn_about_kofi).setOnClickListener {
            openUrl(URL_KOFI)
        }
        findViewById<MaterialButton>(R.id.btn_about_license).setOnClickListener {
            openUrl(URL_NOTICE)
        }
        findViewById<MaterialButton>(R.id.btn_about_maag).setOnClickListener { openUrl(URL_MAAG) }
        findViewById<MaterialButton>(R.id.btn_about_done).setOnClickListener { finish() }
        // Upstream's support channels are for OpenCfMoto, not for this Kove build.
        var card: android.view.View = findViewById(R.id.btn_about_discord)
        while (card !is com.google.android.material.card.MaterialCardView) {
            card = card.parent as android.view.View
        }
        card.visibility = android.view.View.GONE
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, "Couldn't open link", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val URL_WEBSITE = "https://whoismaag.dev"
        private const val URL_MAAG = "https://whoismaag.dev"
        private const val URL_GITHUB = "https://github.com/maagmirror/open-kove"
        private const val URL_DISCORD = "https://discord.gg/xRt5yZy2U"
        const val URL_KOFI = "https://ko-fi.com/alexandrupopa"
        private const val URL_NOTICE =
            "https://github.com/maagmirror/open-kove/blob/main/NOTICE"

        fun start(ctx: Context) {
            ctx.startActivity(Intent(ctx, AboutActivity::class.java))
        }
    }
}
