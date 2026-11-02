// Ported from headunit-revived (AGPLv3): connection/AccessoryConnection.kt
package dev.zanderp.opencfmoto.aa

interface AccessoryConnection {
    val isSingleMessage: Boolean
    fun sendBlocking(buf: ByteArray, length: Int, timeout: Int): Int
    fun recvBlocking(buf: ByteArray, length: Int, timeout: Int, readFully: Boolean): Int
    val isConnected: Boolean
    fun connect(): Boolean
    fun disconnect()
}
