package com.alwayside.android

/** Null capabilities are unknown; an explicit empty collection means no advertised support. */
internal data class RemoteModel(
    val id: String,
    val inputModalities: Set<String>? = null,
    val outputModalities: Set<String>? = null,
    val maxOutputTokens: Int? = null,
    val reasoningLevels: List<String>? = null,
    val generationMethods: Set<String>? = null,
) {
    companion object {
        fun decode(raw: Map<String, Any?>, protocol: ApiProtocol): RemoteModel? {
            val rawId = if (protocol == ApiProtocol.GEMINI) raw["name"] ?: raw["id"] else raw["id"]
            val id = (rawId as? String)?.let {
                if (protocol == ApiProtocol.GEMINI) it.removePrefix("models/") else it
            } ?: return null
            if (id.isBlank() || id.length > 200 || id.any(Char::isISOControl)) return null

            // A malformed field cannot establish either support or lack of support.
            fun strings(value: Any?): List<String>? {
                val values = value as? List<*> ?: return null
                if (values.any { it !is String }) return null
                return values.map { it as String }
            }
            val tokenLimit = raw["max_output_tokens"]
                ?: if (protocol == ApiProtocol.GEMINI) raw["outputTokenLimit"] else null
            val limit = (tokenLimit as? Number)?.toDouble()?.takeIf {
                it.isFinite() && it >= 0 && it <= Int.MAX_VALUE && it % 1.0 == 0.0
            }?.toInt()
            val effort = raw["effort"] as? Map<*, *>
            return RemoteModel(id,
                inputModalities = strings(raw["input_modalities"])?.toSet(),
                outputModalities = strings(raw["output_modalities"])?.toSet(),
                maxOutputTokens = limit,
                reasoningLevels = strings(effort?.get("supported_levels")),
                generationMethods = strings(raw["supportedGenerationMethods"])?.toSet())
        }
    }
}
