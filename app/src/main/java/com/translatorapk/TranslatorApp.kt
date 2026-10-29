package com.translatorapk

import android.app.Application
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class TranslatorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }
}
