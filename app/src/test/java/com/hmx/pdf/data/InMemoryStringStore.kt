package com.hmx.pdf.data

import com.hmx.pdf.data.storage.StringKeyValueStore

class InMemoryStringStore(
    initial: Map<String, String> = emptyMap(),
) : StringKeyValueStore {
    val values = LinkedHashMap<String, String>(initial)

    override fun getString(key: String): String? = values[key]

    override fun putString(key: String, value: String) {
        values[key] = value
    }

    override fun remove(key: String) {
        values.remove(key)
    }
}
