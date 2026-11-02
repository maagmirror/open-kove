// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Free, fully on-device offline routing for a downloaded map area. When the rider downloads an area
 * we also fetch its road network once from the public Overpass (OpenStreetMap) API and build a
 * compact routing graph stored beside the vector tiles. Later, [OfflineRouter] can route to any
 * point inside that area with **no internet** using A* over this graph — road-snapped and
 * oneway-aware, weighted by road class so it prefers bigger roads.
 *
 * This is not as rich as a full GraphHopper/OSRM engine (no turn restrictions), but it's real,
 * costs nothing (Overpass is free, no key, no server) and works while projecting to the bike.
 */
object OfflineRoadGraph {
    private const val MAGIC = 0x4F43524F // 'OCRO'
    private const val VERSION = 1
    private const val DIR = "offline_routing"
    private const val OVERPASS = "https://overpass-api.de/api/interpreter"
    private const val MAX_ROADS_BYTES = 64 * 1024 * 1024 // road JSON can be large for a big area
    private const val SNAP_MAX_M = 3_000.0              // give up if start/dest is off-network

    /** Class code → assumed cruising speed (m/s) used for time-based edge cost / A* heuristic. */
    private val CLASS_SPEED_MS = floatArrayOf(
        30.6f, // 0 motorway  ~110 km/h
        25.0f, // 1 trunk     ~90
        19.4f, // 2 primary   ~70
        16.7f, // 3 secondary ~60
        13.9f, // 4 tertiary  ~50
        11.1f, // 5 unclassified/road ~40
        8.3f,  // 6 residential ~30
        5.6f,  // 7 service   ~20
        4.2f,  // 8 living_street ~15
    )
    private const val MAX_SPEED_MS = 30.6f

    private fun classOf(highway: String): Int = when (highway.removeSuffix("_link")) {
        "motorway" -> 0
        "trunk" -> 1
        "primary" -> 2
        "secondary" -> 3
        "tertiary" -> 4
        "unclassified", "road" -> 5
        "residential" -> 6
        "service" -> 7
        "living_street" -> 8
        else -> 5
    }

    private fun dir(ctx: Context) = File(ctx.filesDir, DIR).apply { mkdirs() }
    private fun fileFor(ctx: Context, name: String): File =
        File(dir(ctx), name.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".graph")

    // ---- loaded-graph cache (keyed by file path + mtime) --------------------------------------

    private val cache = HashMap<String, Graph>()

    fun hasGraphFor(ctx: Context, aLat: Double, aLon: Double, bLat: Double, bLon: Double): Boolean =
        headerCovering(ctx, aLat, aLon, bLat, bLon) != null

    /** A loaded graph whose bbox covers both endpoints, or null. Loads (and caches) on demand. */
    fun forRoute(ctx: Context, aLat: Double, aLon: Double, bLat: Double, bLon: Double): Graph? {
        val f = headerCovering(ctx, aLat, aLon, bLat, bLon) ?: return null
        val key = "${f.absolutePath}@${f.lastModified()}"
        synchronized(cache) {
            cache[key]?.let { return it }
            val g = runCatching { load(f) }.getOrNull() ?: return null
            cache[key] = g
            return g
        }
    }

    private fun headerCovering(
        ctx: Context, aLat: Double, aLon: Double, bLat: Double, bLon: Double,
    ): File? {
        val d = File(ctx.filesDir, DIR)
        if (!d.isDirectory) return null
        val files = d.listFiles { f -> f.isFile && f.name.endsWith(".graph") } ?: return null
        for (f in files) {
            val h = runCatching { readHeader(f) }.getOrNull() ?: continue
            if (aLat in h.south..h.north && aLon in h.west..h.east &&
                bLat in h.south..h.north && bLon in h.west..h.east
            ) return f
        }
        return null
    }

    fun deleteForArea(ctx: Context, name: String) {
        val f = fileFor(ctx, name)
        synchronized(cache) { cache.keys.filter { it.startsWith(f.absolutePath) }.forEach { cache.remove(it) } }
        runCatching { f.delete() }
    }

    // ---- build (download roads + save graph) --------------------------------------------------

    /**
     * Fetch the road network for the bbox and build+save a routing graph for [name]. Runs on the
     * caller's thread (call from a worker). [onProgress] gets short human status lines.
     */
    fun buildForArea(
        ctx: Context,
        name: String,
        south: Double, west: Double, north: Double, east: Double,
        onProgress: (String) -> Unit,
    ) {
        onProgress("Fetching roads for offline routing…")
        val json = fetchRoads(south, west, north, east)
        onProgress("Building routing graph…")
        val built = parse(json)
        if (built.nodeCount < 2 || built.edgeFrom.isEmpty()) {
            throw IOException("No routable roads found for this area")
        }
        save(fileFor(ctx, name), south, west, north, east, built)
        synchronized(cache) { cache.clear() }
        LogBus.log("[route] offline graph built for \"$name\": ${built.nodeCount} nodes, ${built.edgeFrom.size} edges")
    }

    private fun fetchRoads(south: Double, west: Double, north: Double, east: Double): JSONObject {
        // Routable roads only (skip footway/path/cycleway/steps) to keep the download small.
        val cls = "motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|service|road"
        val q = """
            [out:json][timeout:120];
            way["highway"~"^($cls)(_link)?${'$'}"]($south,$west,$north,$east);
            out body;
            >;
            out skel qt;
        """.trimIndent()
        AppHttp.throttle("overpass-api.de", 1_000)
        val body = "data=" + AppHttp.encode(q)
        return JSONObject(
            AppHttp.postForm(OVERPASS, body, readTimeoutMs = 120_000, maxBytes = MAX_ROADS_BYTES),
        )
    }

    private class Built(
        val nodeCount: Int,
        val lat: DoubleArray,
        val lon: DoubleArray,
        val edgeFrom: IntArray,
        val edgeTo: IntArray,
        val edgeDist: FloatArray,
        val edgeClass: ByteArray,
    )

    private fun parse(root: JSONObject): Built {
        val els = root.optJSONArray("elements") ?: throw IOException("Empty Overpass response")
        // First pass: node id → index, collecting coordinates.
        val idToIdx = HashMap<Long, Int>(els.length())
        val latList = ArrayList<Double>()
        val lonList = ArrayList<Double>()
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            if (e.optString("type") != "node") continue
            val id = e.optLong("id")
            if (idToIdx.containsKey(id)) continue
            idToIdx[id] = latList.size
            latList.add(e.getDouble("lat"))
            lonList.add(e.getDouble("lon"))
        }
        val lat = latList.toDoubleArray()
        val lon = lonList.toDoubleArray()

        // Second pass: ways → directed edges.
        val eFrom = ArrayList<Int>()
        val eTo = ArrayList<Int>()
        val eDist = ArrayList<Float>()
        val eCls = ArrayList<Byte>()
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            if (e.optString("type") != "way") continue
            val nodes = e.optJSONArray("nodes") ?: continue
            val tags = e.optJSONObject("tags") ?: continue
            val hw = tags.optString("highway")
            if (hw.isEmpty()) continue
            val klass = classOf(hw).toByte()
            val ow = tags.optString("oneway").lowercase()
            val forwardOnly = ow == "yes" || ow == "true" || ow == "1"
            val backwardOnly = ow == "-1" || ow == "reverse"
            for (k in 0 until nodes.length() - 1) {
                val a = idToIdx[nodes.optLong(k)] ?: continue
                val b = idToIdx[nodes.optLong(k + 1)] ?: continue
                if (a == b) continue
                val d = haversineM(lat[a], lon[a], lat[b], lon[b]).toFloat()
                if (!backwardOnly) { eFrom.add(a); eTo.add(b); eDist.add(d); eCls.add(klass) }
                if (!forwardOnly) { eFrom.add(b); eTo.add(a); eDist.add(d); eCls.add(klass) }
            }
        }
        return Built(
            nodeCount = lat.size,
            lat = lat, lon = lon,
            edgeFrom = eFrom.toIntArray(),
            edgeTo = eTo.toIntArray(),
            edgeDist = eDist.toFloatArray(),
            edgeClass = ByteArray(eCls.size) { eCls[it] },
        )
    }

    // ---- persistence --------------------------------------------------------------------------

    private class Header(val south: Double, val west: Double, val north: Double, val east: Double)

    private fun readHeader(f: File): Header {
        DataInputStream(BufferedInputStream(f.inputStream())).use { i ->
            if (i.readInt() != MAGIC) throw IOException("bad magic")
            if (i.readInt() != VERSION) throw IOException("bad version")
            return Header(i.readDouble(), i.readDouble(), i.readDouble(), i.readDouble())
        }
    }

    private fun save(f: File, south: Double, west: Double, north: Double, east: Double, b: Built) {
        DataOutputStream(BufferedOutputStream(f.outputStream())).use { o ->
            o.writeInt(MAGIC)
            o.writeInt(VERSION)
            o.writeDouble(south); o.writeDouble(west); o.writeDouble(north); o.writeDouble(east)
            o.writeInt(b.nodeCount)
            for (k in 0 until b.nodeCount) { o.writeDouble(b.lat[k]); o.writeDouble(b.lon[k]) }
            o.writeInt(b.edgeFrom.size)
            for (k in b.edgeFrom.indices) {
                o.writeInt(b.edgeFrom[k]); o.writeInt(b.edgeTo[k])
                o.writeFloat(b.edgeDist[k]); o.writeByte(b.edgeClass[k].toInt())
            }
        }
    }

    private fun load(f: File): Graph {
        DataInputStream(BufferedInputStream(f.inputStream())).use { i ->
            if (i.readInt() != MAGIC) throw IOException("bad magic")
            if (i.readInt() != VERSION) throw IOException("bad version")
            val south = i.readDouble(); val west = i.readDouble()
            val north = i.readDouble(); val east = i.readDouble()
            val n = i.readInt()
            val lat = DoubleArray(n); val lon = DoubleArray(n)
            for (k in 0 until n) { lat[k] = i.readDouble(); lon[k] = i.readDouble() }
            val m = i.readInt()
            val from = IntArray(m); val to = IntArray(m)
            val dist = FloatArray(m); val cls = ByteArray(m)
            for (k in 0 until m) {
                from[k] = i.readInt(); to[k] = i.readInt()
                dist[k] = i.readFloat(); cls[k] = i.readByte()
            }
            // Build CSR adjacency (sorted by source node) via counting sort.
            val start = IntArray(n + 1)
            for (k in 0 until m) start[from[k] + 1]++
            for (k in 0 until n) start[k + 1] += start[k]
            val fill = start.copyOf()
            val adjTo = IntArray(m); val adjDist = FloatArray(m); val adjCls = ByteArray(m)
            for (k in 0 until m) {
                val pos = fill[from[k]]++
                adjTo[pos] = to[k]; adjDist[pos] = dist[k]; adjCls[pos] = cls[k]
            }
            return Graph(south, west, north, east, lat, lon, start, adjTo, adjDist, adjCls)
        }
    }

    // ---- the graph + A* router ----------------------------------------------------------------

    class Graph internal constructor(
        val south: Double, val west: Double, val north: Double, val east: Double,
        private val lat: DoubleArray,
        private val lon: DoubleArray,
        private val adjStart: IntArray,
        private val adjTo: IntArray,
        private val adjDist: FloatArray,
        private val adjClass: ByteArray,
    ) {
        private fun nearest(la: Double, lo: Double): Int {
            var best = -1
            var bestD = Double.MAX_VALUE
            for (k in lat.indices) {
                // cheap squared planar distance for the scan; refine with haversine only if needed
                val dLat = lat[k] - la
                val dLon = (lon[k] - lo) * cos(Math.toRadians(la))
                val d = dLat * dLat + dLon * dLon
                if (d < bestD) { bestD = d; best = k }
            }
            if (best < 0) return -1
            return if (haversineM(lat[best], lon[best], la, lo) <= SNAP_MAX_M) best else -1
        }

        /**
         * A* from the nearest node to (fromLat,fromLon) to the nearest node to (toLat,toLon).
         * When [avoidHighways] is true, motorway + trunk edges are skipped (local "no motorway").
         */
        fun route(
            fromLat: Double,
            fromLon: Double,
            toLat: Double,
            toLon: Double,
            avoidHighways: Boolean = false,
        ): OsrmRouter.Route? {
            val start = nearest(fromLat, fromLon)
            val goal = nearest(toLat, toLon)
            if (start < 0 || goal < 0 || start == goal) return null

            val n = lat.size
            val g = FloatArray(n) { Float.MAX_VALUE }
            val came = IntArray(n) { -1 }
            val closed = BooleanArray(n)
            g[start] = 0f
            // Pack (fScoreBits << 32 | nodeIdx) into a long; float bits keep order for positive f.
            val open = PriorityQueue<Long>()
            open.add(pack(heuristic(start, goal), start))
            while (open.isNotEmpty()) {
                val cur = (open.poll() and 0xFFFFFFFFL).toInt()
                if (closed[cur]) continue
                if (cur == goal) break
                closed[cur] = true
                var e = adjStart[cur]
                val end = adjStart[cur + 1]
                while (e < end) {
                    val nxt = adjTo[e]
                    val klass = adjClass[e].toInt()
                    // 0 = motorway, 1 = trunk (often dual-carriageway / expressway).
                    if (avoidHighways && klass <= 1) {
                        e++
                        continue
                    }
                    if (!closed[nxt]) {
                        val speed = CLASS_SPEED_MS.getOrElse(klass) { 11.1f }
                        val cost = adjDist[e] / speed
                        val tentative = g[cur] + cost
                        if (tentative < g[nxt]) {
                            g[nxt] = tentative
                            came[nxt] = cur
                            open.add(pack(tentative + heuristic(nxt, goal), nxt))
                        }
                    }
                    e++
                }
            }
            if (came[goal] < 0 && start != goal) return null

            // Reconstruct node path start..goal.
            val rev = ArrayList<Int>()
            var c = goal
            while (c != -1) { rev.add(c); if (c == start) break; c = came[c] }
            if (rev.last() != start) return null
            val path = IntArray(rev.size) { rev[rev.size - 1 - it] }

            val pts = ArrayList<GpxPoint>(path.size)
            var distM = 0.0
            for (idx in path.indices) {
                pts.add(GpxPoint(lat = lat[path[idx]], lon = lon[path[idx]]))
                if (idx > 0) distM += haversineM(lat[path[idx - 1]], lon[path[idx - 1]], lat[path[idx]], lon[path[idx]])
            }
            return OsrmRouter.Route(
                points = pts,
                distanceM = distM,
                durationSec = g[goal].toDouble(),
                steps = buildSteps(path),
            )
        }

        private fun heuristic(a: Int, goal: Int): Float =
            (haversineM(lat[a], lon[a], lat[goal], lon[goal]) / MAX_SPEED_MS).toFloat()

        private fun buildSteps(path: IntArray): List<OsrmRouter.RouteStep> {
            if (path.size < 2) return emptyList()
            // Maneuver node positions in the path: start, sharp-enough turns, end.
            val turns = ArrayList<Pair<Int, String?>>() // (path index, modifier)
            turns.add(0 to null)
            for (i in 1 until path.size - 1) {
                val bIn = bearing(path[i - 1], path[i])
                val bOut = bearing(path[i], path[i + 1])
                var d = bOut - bIn
                while (d > 180) d -= 360
                while (d < -180) d += 360
                val ad = abs(d)
                if (ad < 25) continue
                val modifier = when {
                    d > 0 && ad < 60 -> "slight right"
                    d > 0 && ad <= 150 -> "right"
                    d > 0 -> "sharp right"
                    ad < 60 -> "slight left"
                    ad <= 150 -> "left"
                    else -> "sharp left"
                }
                turns.add(i to modifier)
            }
            turns.add((path.size - 1) to null)

            val steps = ArrayList<OsrmRouter.RouteStep>(turns.size)
            for (t in turns.indices) {
                val (pi, modifier) = turns[t]
                val node = path[pi]
                val type = when (t) {
                    0 -> "depart"
                    turns.size - 1 -> "arrive"
                    else -> "turn"
                }
                val legM = if (t < turns.size - 1) legLength(path, pi, turns[t + 1].first) else 0.0
                steps.add(
                    OsrmRouter.RouteStep(
                        maneuverType = type,
                        modifier = modifier,
                        roadName = "",
                        maneuverLat = lat[node],
                        maneuverLon = lon[node],
                        distanceM = legM,
                        exit = null,
                        lanes = null,
                    ),
                )
            }
            return steps
        }

        private fun legLength(path: IntArray, a: Int, b: Int): Double {
            var m = 0.0
            for (k in a until b) m += haversineM(lat[path[k]], lon[path[k]], lat[path[k + 1]], lon[path[k + 1]])
            return m
        }

        private fun bearing(a: Int, b: Int): Double {
            val lat1 = Math.toRadians(lat[a]); val lat2 = Math.toRadians(lat[b])
            val dLon = Math.toRadians(lon[b] - lon[a])
            val y = sin(dLon) * cos(lat2)
            val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
            return (Math.toDegrees(atan2(y, x)) + 360) % 360
        }

        private fun pack(f: Float, idx: Int): Long =
            (java.lang.Float.floatToRawIntBits(if (f < 0f) 0f else f).toLong() shl 32) or
                (idx.toLong() and 0xFFFFFFFFL)
    }

    private fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).let { it * it }
        return 2 * r * asin(min(1.0, sqrt(a)))
    }
}
