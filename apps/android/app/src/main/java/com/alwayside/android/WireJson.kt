package com.alwayside.android

import org.json.JSONArray
import org.json.JSONObject

/** Android JSON is confined to the transport/storage boundary; protocol state machines use maps. */
internal object WireJson {
    fun encode(value: Map<String, Any?>): String = objectValue(value).toString()
    fun decode(text: String): Map<String, Any?> = unwrap(JSONObject(text)).wireObject()
    private fun objectValue(value: Map<String, Any?>): JSONObject = JSONObject().apply {
        value.forEach { (key, item) -> put(key, wrap(item)) }
    }
    private fun wrap(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> objectValue(value.wireObject())
        is List<*> -> JSONArray().apply { value.forEach { put(wrap(it)) } }
        else -> value
    }
    private fun unwrap(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> value.keys().asSequence().associateWith { unwrap(value.get(it)) }
        is JSONArray -> (0 until value.length()).map { unwrap(value.get(it)) }
        else -> value
    }
}
