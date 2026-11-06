package com.alwayside.android

/** Reset service bindings without depending on a readable service catalog or touching conversations. */
internal object SettingsRecovery {
    fun reset(data: Map<String, Any?>?): Map<String, Any?> {
        val defaults = AppConfig()
        val prompts = linkedMapOf("instructions" to defaults.instructions, "defaultQuestion" to defaults.defaultQuestion,
            "audioQuestion" to defaults.audioQuestion, "textQuestion" to defaults.textQuestion,
            "transcriptionPrompt" to defaults.transcriptionPrompt)
        return linkedMapOf<String, Any?>("schemaVersion" to 4,
            "services" to ServiceCatalogCodec.encode(ServiceCatalog())).apply {
            prompts.forEach { (name, fallback) -> put(name, data?.get(name) as? String ?: fallback) }
            put("autoUpdateImage", data?.get("autoUpdateImage") as? Boolean ?: defaults.autoUpdateImage)
            put("audioOutput", (data?.get("audioOutput") as? String)?.takeIf {
                it in setOf("transcript", "translate", "interpret")
            } ?: defaults.audioOutput)
        }
    }
}
