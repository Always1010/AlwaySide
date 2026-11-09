package com.alwayside.android

import java.io.File

internal class StorageReadingIndex(private val runtime: StorageRuntime) : ReadingCacheIndex {
    private fun path(file: File) = file.relativeTo(runtime.context.noBackupFilesDir).invariantSeparatorsPath
    override fun used(file: File, owner: String) {
        runtime.register(file, "reading", owner)
        val known = runtime.catalog.knownOwners().associateBy(::readingDigest)
        val refs = File(file.parentFile, file.nameWithoutExtension + ".refs")
        if (refs.isFile) {
            val hashes = runCatching { java.util.Properties().apply { refs.inputStream().use(::load) }.stringPropertyNames() }.getOrDefault(emptySet())
            hashes.mapNotNull(known::get).forEach { previousOwner ->
                if (previousOwner !in runtime.catalog.references(path(file)))
                    runtime.catalog.bind(path(file), previousOwner, "selection:${file.nameWithoutExtension}")
            }
        }
        // Replays of an existing mark must not create a second, permanent ownership reference.
        if (owner !in runtime.catalog.references(path(file)))
            runtime.catalog.bind(path(file), owner, "selection:${file.nameWithoutExtension}")
    }
    override fun marked(file: File, owner: String, mark: String) {
        runtime.catalog.published(owner, "reading")
        runtime.register(file, "reading", owner)
        runtime.catalog.bind(path(file), owner, "mark:$mark")
        runtime.catalog.unbind(owner, "selection:${file.nameWithoutExtension}")
    }
    override fun removed(owner: String, mark: String) = runtime.catalog.unbind(owner, "mark:$mark")
    override fun unreferenced(owner: String, key: String) {
        runtime.catalog.unbind(owner, "selection:$key")
        runtime.catalog.unbind(owner, "legacy:$key")
    }
    override fun removeOwner(owner: String) = runtime.catalog.unbind(owner)
    override fun forgotten(file: File) = runtime.catalog.forget(path(file))
}
