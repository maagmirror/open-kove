// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Serialize a recorded [Trip] to GPX 1.1 for share / export. */
object TripGpx {
    private val isoUtc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val fileStamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US)

    fun suggestedFileName(trip: Trip): String =
        "opencfmoto_trip_${fileStamp.format(Date(trip.start))}.gpx"

    fun toXml(trip: Trip): String {
        val name = escape("OpenCfMoto ${trip.dateText()} ${trip.timeRangeText()}")
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<gpx version="1.1" creator="OpenCfMoto" """)
        sb.append("""xmlns="http://www.topografix.com/GPX/1/1">""").append('\n')
        sb.append("  <metadata>\n")
        sb.append("    <name>").append(name).append("</name>\n")
        sb.append("    <time>").append(isoUtc.format(Date(trip.start))).append("</time>\n")
        sb.append("  </metadata>\n")
        sb.append("  <trk>\n")
        sb.append("    <name>").append(name).append("</name>\n")
        sb.append("    <type>motorcycle</type>\n")
        sb.append("    <trkseg>\n")
        for (p in trip.points) {
            sb.append("      <trkpt lat=\"")
                .append(fmt(p.lat))
                .append("\" lon=\"")
                .append(fmt(p.lon))
                .append("\">\n")
            if (p.time > 0L) {
                sb.append("        <time>").append(isoUtc.format(Date(p.time))).append("</time>\n")
            }
            sb.append("      </trkpt>\n")
        }
        sb.append("    </trkseg>\n")
        sb.append("  </trk>\n")
        sb.append("</gpx>\n")
        return sb.toString()
    }

    private fun fmt(v: Double): String = String.format(Locale.US, "%.6f", v)

    private fun escape(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
