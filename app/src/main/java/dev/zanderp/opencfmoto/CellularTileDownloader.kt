// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.util.Log
import org.osmdroid.api.IMapView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.modules.CantContinueException
import org.osmdroid.tileprovider.modules.IFilesystemCache
import org.osmdroid.tileprovider.modules.TileDownloader
import org.osmdroid.tileprovider.tilesource.BitmapTileSourceBase
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.util.Counters
import org.osmdroid.tileprovider.util.StreamUtils
import org.osmdroid.util.MapTileIndex
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException

/**
 * osmdroid [TileDownloader] that opens tile HTTP via [AppHttp.openUrl] so MAPNIK tiles use the
 * phone's cellular/internet network while the process is bound to bike Wi‑Fi (no uplink).
 */
class CellularTileDownloader : TileDownloader() {
    @Volatile
    private var loggedBind = false

    override fun downloadTile(
        pMapTileIndex: Long,
        redirectCount: Int,
        targetUrl: String,
        pFilesystemCache: IFilesystemCache?,
        pTileSource: OnlineTileSourceBase,
    ): Drawable? {
        if (redirectCount > 3) return null

        var userAgent: String? = null
        if (pTileSource.tileSourcePolicy.normalizesUserAgent()) {
            userAgent = Configuration.getInstance().normalizedUserAgent
        }
        if (userAgent == null) {
            userAgent = Configuration.getInstance().userAgentValue
        }
        if (!pTileSource.tileSourcePolicy.acceptsUserAgent(userAgent)) {
            Log.e(IMapView.LOGTAG, "Please configure a relevant user agent; current value is: $userAgent")
            return null
        }

        var input: InputStream? = null
        var output: OutputStream? = null
        var conn: HttpURLConnection? = null
        var byteStream: ByteArrayInputStream? = null
        var dataStream: ByteArrayOutputStream? = null
        try {
            if (TextUtils.isEmpty(targetUrl)) return null
            if (Configuration.getInstance().isDebugMode) {
                Log.d(IMapView.LOGTAG, "Downloading Maptile from url: $targetUrl")
            }

            conn = AppHttp.openUrl(targetUrl)
            if (!loggedBind) {
                loggedBind = true
                val msg = when {
                    AppHttp.isCellularPin() -> "[OSM] tiles pinned to cellular"
                    AppHttp.hasInternetPin() -> "[OSM] tiles pinned to internet network"
                    else -> "[OSM] tiles via process default route (no internet pin)"
                }
                LogBus.log(msg)
            }
            conn.useCaches = true
            conn.setRequestProperty(Configuration.getInstance().userAgentHttpHeader, userAgent)
            for ((k, v) in Configuration.getInstance().additionalHttpRequestProperties) {
                conn.setRequestProperty(k, v)
            }
            conn.connect()

            val code = conn.responseCode
            if (code != 200) {
                when (code) {
                    301, 302, 307, 308 -> {
                        if (Configuration.getInstance().isMapTileDownloaderFollowRedirects) {
                            var redirectUrl = conn.getHeaderField("Location")
                            if (redirectUrl != null) {
                                if (redirectUrl.startsWith("/")) {
                                    val old = URL(targetUrl)
                                    var port = old.port
                                    val secure = targetUrl.lowercase().startsWith("https://")
                                    if (port == -1) {
                                        port = if (targetUrl.lowercase().startsWith("http://")) 80 else 443
                                    }
                                    redirectUrl =
                                        (if (secure) "https://" else "http://") + old.host + ":" + port + redirectUrl
                                }
                                Log.i(
                                    IMapView.LOGTAG,
                                    "Http redirect for MapTile: ${MapTileIndex.toString(pMapTileIndex)} " +
                                        "HTTP response: ${conn.responseMessage} to url $redirectUrl",
                                )
                                return downloadTile(
                                    pMapTileIndex,
                                    redirectCount + 1,
                                    redirectUrl,
                                    pFilesystemCache,
                                    pTileSource,
                                )
                            }
                        }
                        Counters.tileDownloadErrors++
                        input = conn.errorStream
                        return null
                    }
                    else -> {
                        Log.w(
                            IMapView.LOGTAG,
                            "Problem downloading MapTile: ${MapTileIndex.toString(pMapTileIndex)} " +
                                "HTTP response: ${conn.responseMessage}",
                        )
                        Counters.tileDownloadErrors++
                        input = conn.errorStream
                        return null
                    }
                }
            }

            val mime = conn.getHeaderField("Content-Type")
            if (mime != null && !mime.lowercase().contains("image")) {
                Log.w(IMapView.LOGTAG, "$targetUrl success, however mime is not an image: $mime")
            }

            input = conn.inputStream
            dataStream = ByteArrayOutputStream()
            output = BufferedOutputStream(dataStream, StreamUtils.IO_BUFFER_SIZE)
            val expirationTime = pTileSource.tileSourcePolicy.computeExpirationTime(
                conn,
                System.currentTimeMillis(),
            )
            StreamUtils.copy(input, output)
            output.flush()
            val data = dataStream.toByteArray()
            byteStream = ByteArrayInputStream(data)
            if (pFilesystemCache != null) {
                pFilesystemCache.saveFile(pTileSource, pMapTileIndex, byteStream, expirationTime)
                byteStream.reset()
            }
            return pTileSource.getDrawable(byteStream)
        } catch (_: UnknownHostException) {
            Counters.tileDownloadErrors++
            Log.w(IMapView.LOGTAG, "UnknownHostException downloading MapTile: ${MapTileIndex.toString(pMapTileIndex)}")
        } catch (e: BitmapTileSourceBase.LowMemoryException) {
            Counters.countOOM++
            Log.w(IMapView.LOGTAG, "LowMemoryException downloading MapTile: ${MapTileIndex.toString(pMapTileIndex)} : $e")
            throw CantContinueException(e)
        } catch (_: FileNotFoundException) {
            Counters.tileDownloadErrors++
            Log.w(IMapView.LOGTAG, "Tile not found: ${MapTileIndex.toString(pMapTileIndex)}")
        } catch (e: IOException) {
            Counters.tileDownloadErrors++
            Log.w(IMapView.LOGTAG, "IOException downloading MapTile: ${MapTileIndex.toString(pMapTileIndex)} : $e")
        } catch (e: Throwable) {
            Counters.tileDownloadErrors++
            Log.e(IMapView.LOGTAG, "Error downloading MapTile: ${MapTileIndex.toString(pMapTileIndex)}", e)
        } finally {
            StreamUtils.closeStream(input)
            StreamUtils.closeStream(output)
            StreamUtils.closeStream(byteStream)
            StreamUtils.closeStream(dataStream)
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
        return null
    }
}
