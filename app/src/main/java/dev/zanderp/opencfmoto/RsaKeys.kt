package dev.zanderp.opencfmoto

import android.content.Context
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

/**
 * Mirrors net.easyconn.carman.utils.RSAUtil.
 *
 * The Carbit SDK generates a 1024-bit RSA keypair locally on first run.
 *   - The bike encrypts a HUID with our public key  → we decrypt with private key (decryptByPrivateKey)
 *   - The bike sends `huid` cleartext in CLIENT_INFO → we sign by encrypting with the private key
 *     (encryptHUID), bike verifies with our public key.
 *
 * ## Why the keypair is persisted
 * "On first run" is the point: the vendor client keeps **one** keypair for the life of the install,
 * so a dash sees the same client identity every ride. This used to generate a fresh keypair per
 * process, which made the phone a brand-new client on every app start — harmless on dashes that
 * only verify the signature against the pubkey in the same message, but wrong for any dash that
 * remembers the phone it was paired with. Persisting costs nothing and matches the SDK.
 *
 * [init] is called from [OpenCfMotoApp]; without it (unit tests, early access) the keypair falls
 * back to an in-memory one so nothing can crash for want of a Context.
 */
object RsaKeys {
    private const val PREFS = "opencfmoto_rsa"
    private const val KEY_PUBLIC = "pub_x509_b64"
    private const val KEY_PRIVATE = "priv_pkcs8_b64"

    @Volatile private var stored: KeyPair? = null

    /** Load the persisted keypair, generating and saving one the first time. */
    fun init(context: Context) {
        if (stored != null) return
        synchronized(this) {
            if (stored != null) return
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val pub = prefs.getString(KEY_PUBLIC, null)
            val priv = prefs.getString(KEY_PRIVATE, null)
            if (pub != null && priv != null) {
                val restored = runCatching {
                    val kf = KeyFactory.getInstance("RSA")
                    KeyPair(
                        kf.generatePublic(X509EncodedKeySpec(Base64.decode(pub, Base64.NO_WRAP))),
                        kf.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(priv, Base64.NO_WRAP))),
                    )
                }.getOrNull()
                if (restored != null) {
                    stored = restored
                    return
                }
            }
            val fresh = generate()
            stored = fresh
            prefs.edit()
                .putString(KEY_PUBLIC, Base64.encodeToString(fresh.public.encoded, Base64.NO_WRAP))
                .putString(KEY_PRIVATE, Base64.encodeToString(fresh.private.encoded, Base64.NO_WRAP))
                .apply()
        }
    }

    private fun generate(): KeyPair = KeyPairGenerator.getInstance("RSA")
        .apply { initialize(1024) }
        .generateKeyPair()

    /** In-memory fallback for callers that run before [init]. */
    private val fallback: KeyPair by lazy { generate() }

    private val keypair: KeyPair get() = stored ?: fallback

    val publicKey: PublicKey get() = keypair.public
    val privateKey: PrivateKey get() = keypair.private

    /** Base64-encoded X.509 public key (same format the SDK puts in CLIENT_INFO.pubkey). */
    val publicKeyBase64: String get() = Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)

    /** "Encrypt" HUID with private key (= signing) — the bike will decrypt with our pubkey. */
    fun signHuid(huid: String): String {
        val priv = KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(privateKey.encoded))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, priv)
        return Base64.encodeToString(cipher.doFinal(huid.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    /** Decrypt with our private key. Bike encrypted the input with our pubkey. */
    fun decryptWithPrivateKey(encrypted: ByteArray): ByteArray {
        val priv = KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(privateKey.encoded))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, priv)
        return cipher.doFinal(encrypted)
    }
}
