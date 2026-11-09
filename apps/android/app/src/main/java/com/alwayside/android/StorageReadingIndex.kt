package com.alwayside.android

import java.io.File

internal class StorageReadingIndex(private val runtime: StorageRuntime) : ReadingCacheIndex {
    private fun path(file: File) = file.relativeTo(runtime.context.noBackupFilesDir).invariantSeparatorsPath
    override fun used(file: File, owner: String) {
        runtime.register(file, "reading", owner)
        // Replays of an existing mark must not create a second, permanent ownership reference.
        if (owner !in runtime.catalog.references(path(file)))
            runtime.catalog.bind(path(file), owner, "selection:${file.nameWithoutExtension}")
    }
    override fun marked(file: File, owner: String, mark: String) {
        runtime.register(file, "reading", owner)
        runtime.catalog.bind(path(file), owner, "mark:$mark")
        runtime.catalog.unbind(owner, "selection:${file.nameWithoutExtension}")
    }
    override fun removed(owner: String, mark: String) = runtime.catalog.unbind(owner, "mark:$mark")
    override fun removeOwner(owner: String) = runtime.catalog.unbind(owner)
    override fun forgotten(file: File) = runtime.catalog.forget(path(file))
}
