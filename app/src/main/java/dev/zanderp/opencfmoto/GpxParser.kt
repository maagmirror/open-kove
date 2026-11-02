// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import java.io.File

data class GpxPoint(val lat: Double, val lon: Double, val ele: Double? = null, val name: String? = null)

data class GpxTrack(
    val name: String,
    val points: List<GpxPoint>,
    val waypoints: List<GpxPoint>,
)

/**
 * Minimal GPX reader — tracks (`trkpt`), routes (`rtept`), and waypoints (`wpt`).
 * Regex-based so it runs on device and JVM unit tests (no XmlPullParser factory needed).
 */
object GpxParser {
    private val pointTag = Regex(
        """<(trkpt|rtept|wpt)\b([^>]*)>([\s\S]*?)</\1>""",
        RegexOption.IGNORE_CASE,
    )
    private val selfClose = Regex(
        """<(trkpt|rtept|wpt)\b([^>]*)/>""",
        RegexOption.IGNORE_CASE,
    )
    private val latAttr = Regex("""\blat\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val lonAttr = Regex("""\blon\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val eleTag = Regex("""<ele[^>]*>\s*([^<]+)\s*</ele>""", RegexOption.IGNORE_CASE)
    private val nameTag = Regex("""<name[^>]*>\s*([^<]+)\s*</name>""", RegexOption.IGNORE_CASE)
    private val trkName = Regex(
        """<trk\b[^>]*>[\s\S]*?<name[^>]*>\s*([^<]+)\s*</name>""",
        RegexOption.IGNORE_CASE,
    )
    private val rteName = Regex(
        """<rte\b[^>]*>[\s\S]*?<name[^>]*>\s*([^<]+)\s*</name>""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(file: File): GpxTrack {
        val xml = file.readText()
        val trackPts = ArrayList<GpxPoint>()
        val routePts = ArrayList<GpxPoint>()
        val waypoints = ArrayList<GpxPoint>()

        fun ingest(tag: String, attrs: String, body: String) {
            val lat = latAttr.find(attrs)?.groupValues?.get(1)?.toDoubleOrNull() ?: return
            val lon = lonAttr.find(attrs)?.groupValues?.get(1)?.toDoubleOrNull() ?: return
            val ele = eleTag.find(body)?.groupValues?.get(1)?.toDoubleOrNull()
            val name = nameTag.find(body)?.groupValues?.get(1)?.trim()
            val pt = GpxPoint(lat, lon, ele, name)
            when (tag.lowercase()) {
                "trkpt" -> trackPts.add(pt)
                "rtept" -> routePts.add(pt)
                "wpt" -> waypoints.add(pt)
            }
        }

        for (m in pointTag.findAll(xml)) {
            ingest(m.groupValues[1], m.groupValues[2], m.groupValues[3])
        }
        for (m in selfClose.findAll(xml)) {
            ingest(m.groupValues[1], m.groupValues[2], "")
        }

        val name = trkName.find(xml)?.groupValues?.get(1)?.trim()
            ?: rteName.find(xml)?.groupValues?.get(1)?.trim()
            ?: file.nameWithoutExtension

        val points = when {
            trackPts.isNotEmpty() -> trackPts
            routePts.isNotEmpty() -> routePts
            else -> emptyList()
        }
        return GpxTrack(name, points, waypoints)
    }
}
