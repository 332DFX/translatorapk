package com.translatorapk.core

import android.content.Context
import com.android.apksig.ApkSigner
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date

object ApkSignerUtil {

    private const val ALIAS = "translatorapk"

    fun sign(context: Context, input: File, output: File) {
        ensureProvider()
        val (privateKey, certificate) = loadOrCreateKey(context)
        output.delete()
        val signerConfig = ApkSigner.SignerConfig.Builder(
            ALIAS,
            privateKey,
            listOf(certificate)
        ).build()

        ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(input)
            .setOutputApk(output)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setMinSdkVersion(26)
            .build()
            .sign()
    }

    fun keystoreInfo(context: Context): String {
        val file = File(context.filesDir, KEYSTORE_FILE)
        return if (file.exists()) "Используется постоянный ключ приложения" else "Ключ будет создан при первой сборке"
    }

    private fun ensureProvider() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    private fun loadOrCreateKey(context: Context): Pair<PrivateKey, X509Certificate> {
        val keyStoreFile = File(context.filesDir, KEYSTORE_FILE)
        val password = password(context)
        val keyStore = KeyStore.getInstance("PKCS12")

        if (keyStoreFile.exists()) {
            keyStoreFile.inputStream().use { keyStore.load(it, password.toCharArray()) }
            val key = keyStore.getKey(ALIAS, password.toCharArray()) as PrivateKey
            val certificate = keyStore.getCertificate(ALIAS) as X509Certificate
            return key to certificate
        }

        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val certificate = generateCertificate(keyPair)
        keyStore.load(null, password.toCharArray())
        keyStore.setKeyEntry(ALIAS, keyPair.private, password.toCharArray(), arrayOf(certificate))
        keyStoreFile.outputStream().use { keyStore.store(it, password.toCharArray()) }
        return keyPair.private to certificate
    }

    private fun password(context: Context): String {
        val file = File(context.filesDir, PASSWORD_FILE)
        if (file.exists()) return file.readText()
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        val random = SecureRandom()
        val value = (1..32).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
        file.writeText(value)
        return value
    }

    private fun generateCertificate(keyPair: KeyPair): X509Certificate {
        val now = System.currentTimeMillis()
        val name = X500Name("CN=APK Translator, O=TranslatorAPK")
        val builder = JcaX509v3CertificateBuilder(
            name,
            BigInteger.valueOf(now),
            Date(now - 86_400_000L),
            Date(now + 10L * 365L * 86_400_000L),
            name,
            keyPair.public
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .build(keyPair.private)
        return JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .getCertificate(builder.build(signer))
    }

    private const val KEYSTORE_FILE = "translator-signing.p12"
    private const val PASSWORD_FILE = "translator-signing.pass"
}
