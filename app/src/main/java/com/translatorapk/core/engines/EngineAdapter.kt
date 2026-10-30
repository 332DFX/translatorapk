package com.translatorapk.core.engines

import com.translatorapk.model.EngineType

interface EngineAdapter {
    val type: EngineType
    fun matches(entries: List<String>): Boolean
    fun limitation(): String? = null
}

object GodotEngine : EngineAdapter {
    override val type = EngineType.GODOT
    override fun matches(entries: List<String>): Boolean =
        entries.any { it.endsWith(".pck") || it.contains("libgodot", ignoreCase = true) }

    override fun limitation() =
        "Текстовые файлы внутри PCK и APK (PO/CSV/JSON/TXT) переводятся, PCK пересобирается. " +
            "Бинарные .translation пока не изменяются."
}

object UnityEngine : EngineAdapter {
    override val type = EngineType.UNITY
    override fun matches(entries: List<String>): Boolean =
        entries.any { it.startsWith("assets/bin/Data/") || it.endsWith("libunity.so") }

    override fun limitation() =
        "TextAsset в несжатых serialized-файлах переводятся и пересобираются. " +
            "AssetBundle (data.unity3d) и IL2CPP пока не изменяются."
}

object UnrealEngine : EngineAdapter {
    override val type = EngineType.UNREAL
    override fun matches(entries: List<String>): Boolean =
        entries.any {
            it.endsWith(".locres") || it.endsWith(".pak") || it.endsWith(".utoc") || it.endsWith(".ucas")
        }

    override fun limitation() =
        "Бинарные .locres/.pak пока не изменяются. Обрабатываются только текстовые файлы."
}

object NativeAndroidEngine : EngineAdapter {
    override val type = EngineType.NATIVE
    override fun matches(entries: List<String>): Boolean =
        entries.contains("resources.arsc") || entries.contains("classes.dex")

    override fun limitation() =
        "Переводятся строки resources.arsc (default/string) и текстовые assets. " +
            "Плюралы, массивы и строки в коде пока не изменяются."
}

object GenericEngine : EngineAdapter {
    override val type = EngineType.GENERIC
    override fun matches(entries: List<String>): Boolean = true
}

object EngineRegistry {
    private val adapters: List<EngineAdapter> = listOf(
        GodotEngine,
        UnityEngine,
        UnrealEngine,
        NativeAndroidEngine,
        GenericEngine
    )

    fun detect(entries: List<String>): EngineAdapter =
        adapters.firstOrNull { it.matches(entries) } ?: GenericEngine
}
