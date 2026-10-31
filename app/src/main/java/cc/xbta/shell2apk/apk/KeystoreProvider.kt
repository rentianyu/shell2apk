package cc.xbta.shell2apk.apk

import android.content.Context
import com.android.apksig.ApkSigner
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * 用内置 keystore 给生成出来的 APK 签名（v1+v2+v3）。
 * keystore 随 App 一起打包在 assets 里（自用工具，签名私钥公开不影响功能）。
 */
object KeystoreProvider {

    private const val ALIAS = "shell2apk"
    private const val PASSWORD = "s2a7b678f45cfba8ca074"

    fun sign(ctx: Context, input: File, output: File) {
        val ks = KeyStore.getInstance("PKCS12")
        ctx.assets.open("keystore.p12").use { ks.load(it, PASSWORD.toCharArray()) }
        val key = ks.getKey(ALIAS, PASSWORD.toCharArray()) as PrivateKey
        val chain = ks.getCertificateChain(ALIAS).map { it as X509Certificate }
        val cfg = ApkSigner.SignerConfig.Builder(ALIAS, key, chain).build()
        ApkSigner.Builder(listOf(cfg))
            .setInputApk(input)
            .setOutputApk(output)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setMinSdkVersion(26)
            .build()
            .sign()
    }
}
