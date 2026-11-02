// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.os.Build
import java.lang.reflect.Method

/**
 * Lifts Android's non-SDK interface restrictions for this process, so reflection can reach platform
 * fields the blocklist otherwise hides.
 *
 * ## Why this is needed
 * A hidden member does not report itself as restricted — it reports itself as **absent**. Reflecting
 * `WifiP2pConfig.networkName` throws `NoSuchFieldException`, and `getDeclaredFields()` returns a
 * filtered list (on this phone, only `deviceAddress`), which reads exactly like "the platform
 * removed the field". It didn't: the field is there and the vendor client writes to it, which is why
 * its Wi‑Fi Direct group is called `Easyconn_AP-xxxx` while ours was stuck on the framework's
 * `DIRECT-…` default.
 *
 * ## How
 * The same route the vendor takes — it ships `me.weishu.reflection` (free-reflection) and calls
 * `Reflection.unseal()` on startup. The trick is meta-reflection: obtain
 * `dalvik.system.VMRuntime.setHiddenApiExemptions` *through* `Class.getDeclaredMethod`, so the
 * caller the runtime sees is `java.lang.Class` — a platform class, which is not subject to the
 * check. Then exempt every signature with the prefix `"L"`.
 *
 * Only the bootstrap part is reproduced here. The library also carries a fallback that writes a tiny
 * DEX to the code cache and calls the same method from there; it is not needed while the direct
 * route works, and [unseal] reports whether it did.
 */
object HiddenApi {

    @Volatile private var done = false
    @Volatile private var ok = false

    /** Idempotent; returns whether the exemption is in place. */
    @Synchronized
    fun unseal(context: android.content.Context? = null, log: ((String) -> Unit)? = null): Boolean {
        if (done) return ok
        done = true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            ok = true
            return true
        }
        ok = exemptAllViaBootstrap(log)
        if (!ok && context != null) ok = exemptViaOwnDex(context, log)
        log?.invoke("[hidden-api] non-SDK reflection ${if (ok) "unsealed" else "still restricted"}")
        return ok
    }

    /**
     * Second route, the one the vendor falls back to. A class loaded with a **null** class loader is
     * attributed to the bootstrap loader, i.e. treated as platform code, and platform code is not
     * subject to the non-SDK check — so the very same [exemptAllViaBootstrap] below succeeds when
     * called from that copy of this class. The vendor ships a small purpose-built DEX for this;
     * re-loading our own APK avoids carrying someone else's binary.
     */
    private fun exemptViaOwnDex(context: android.content.Context, log: ((String) -> Unit)?): Boolean =
        try {
            val dex = java.io.File(context.codeCacheDir, BOOTSTRAP_DEX)
            if (!dex.exists() || dex.length() == 0L) {
                context.assets.open(BOOTSTRAP_DEX).use { input ->
                    dex.outputStream().use { input.copyTo(it) }
                }
            }
            // Since Android 10 the runtime refuses to open a dex it could still be written to:
            // "SecurityException: Writable dex file '…' is not allowed." Drop the write bit — also
            // on a file left behind writable by an earlier run.
            if (dex.canWrite()) dex.setReadOnly()
            val dexFileClass = Class.forName("dalvik.system.DexFile")
            val dexFile = dexFileClass.getConstructor(String::class.java).newInstance(dex.absolutePath)
            val loadClass = dexFileClass.getMethod(
                "loadClass", String::class.java, ClassLoader::class.java,
            )
            val bootstrapped = loadClass.invoke(dexFile, BOOTSTRAP_CLASS, null) as Class<*>
            bootstrapped.getDeclaredMethod("exemptAll").invoke(null) as Boolean
        } catch (t: Throwable) {
            log?.invoke("[hidden-api] bootstrap-loader route failed: ${generateSequence(t) { it.cause }.last()}")
            false
        }

    /**
     * FreeReflection's bootstrap, shipped as an asset. It has to be a **separate, dependency-free**
     * DEX: `loadClass(name, null)` resolves the class's references against the boot class loader, so
     * anything touching the Kotlin stdlib — as re-loading our own APK does — dies with
     * `NoClassDefFoundError: Class not found using the boot class loader`. This one imports nothing
     * beyond `java.lang` and `android.os.Build`.
     *
     * Third-party: FreeReflection by weishu, MIT. See NOTICE.
     */
    private const val BOOTSTRAP_DEX = "freereflection_bootstrap.dex"
    private const val BOOTSTRAP_CLASS = "me.weishu.reflection.BootstrapClass"

    private fun exemptAllViaBootstrap(log: ((String) -> Unit)?): Boolean {
        return try {
            val classArrayType: Class<*> = emptyArray<Class<*>>().javaClass
            val stringArrayType: Class<*> = emptyArray<String>().javaClass

            val forName = Class::class.java
                .getDeclaredMethod("forName", String::class.java)
            val getDeclaredMethod = Class::class.java
                .getDeclaredMethod("getDeclaredMethod", String::class.java, classArrayType)

            val vmRuntimeClass = forName.invoke(null, "dalvik.system.VMRuntime") as Class<*>
            val getRuntime = getDeclaredMethod
                .invoke(vmRuntimeClass, "getRuntime", null) as Method
            val setExemptions = getDeclaredMethod
                .invoke(vmRuntimeClass, "setHiddenApiExemptions", arrayOf(stringArrayType)) as Method

            val vmRuntime = getRuntime.invoke(null)
            setExemptions.invoke(vmRuntime, arrayOf("L"))
            true
        } catch (t: Throwable) {
            // Every step here is itself a reflective call, so whatever actually failed arrives
            // wrapped: a missing `setHiddenApiExemptions` surfaces as InvocationTargetException
            // caused by NoSuchMethodException, which says something very different from the call
            // being rejected. Unwrap or the message is useless.
            val cause = generateSequence(t) { it.cause }.last()
            log?.invoke("[hidden-api] direct route failed: $t / root cause: $cause")
            false
        }
    }
}
