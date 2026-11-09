package com.alwayside.android

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

/** One transactional authority for history, resource ownership and unfinished deletions. */
internal class StorageCatalog(context: Context) {
    private val db = SQLiteDatabase.openOrCreateDatabase(File(context.noBackupFilesDir, "storage_v1.db"), null)
    init {
        db.execSQL("CREATE TABLE IF NOT EXISTS metadata (name TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS history (id TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS deleted (owner TEXT PRIMARY KEY)")
        db.execSQL("CREATE TABLE IF NOT EXISTS cleanup (owner TEXT NOT NULL, kind TEXT NOT NULL, error TEXT NOT NULL DEFAULT '', PRIMARY KEY(owner,kind))")
        db.execSQL("CREATE TABLE IF NOT EXISTS assets (path TEXT PRIMARY KEY, kind TEXT NOT NULL, owner TEXT NOT NULL, idle INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE IF NOT EXISTS asset_refs (path TEXT NOT NULL, owner TEXT NOT NULL, slot TEXT NOT NULL, PRIMARY KEY(owner,slot))")
        db.execSQL("CREATE INDEX IF NOT EXISTS asset_refs_path ON asset_refs(path)")
    }

    @Synchronized fun initialized(): Boolean = db.rawQuery("SELECT 1 FROM metadata WHERE name='history'", null).use { it.moveToFirst() }
    @Synchronized fun manifest(): ByteArray? {
        val metadata = db.rawQuery("SELECT value FROM metadata WHERE name='history'", null).use {
            if (it.moveToFirst()) WireJson.decode(it.getString(0)) else return null
        }
        val records = db.rawQuery("SELECT value FROM history ORDER BY rowid", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(WireJson.decode(cursor.getString(0))) }
        }
        return WireJson.encode(metadata + ("records" to records)).toByteArray(Charsets.UTF_8)
    }

    /** The removal decision and every cleanup job commit together; file I/O happens later. */
    @Synchronized fun commit(bytes: ByteArray) {
        val root = WireJson.decode(bytes.toString(Charsets.UTF_8))
        val entries = (root["records"] as List<*>).map { @Suppress("UNCHECKED_CAST") (it as Map<String, Any?>) }
        val next = entries.map { it.getValue("id") as String }.toSet()
        db.beginTransaction()
        try {
            val old = db.rawQuery("SELECT id FROM history", null).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            (old - next).forEach { owner ->
                db.execSQL("INSERT OR IGNORE INTO deleted(owner) VALUES(?)", arrayOf(owner))
                listOf("images", "audio", "subtitles", "reading").forEach { kind -> enqueueInTransaction(owner, kind) }
            }
            db.execSQL("DELETE FROM history")
            entries.forEach { entry ->
                val owner = entry.getValue("id") as String
                check(!isDeleted(owner)) { "这段会话已删除，请新建会话" }
                db.execSQL("INSERT INTO history(id,value) VALUES(?,?)", arrayOf(owner, WireJson.encode(entry)))
            }
            db.execSQL("INSERT OR REPLACE INTO metadata(name,value) VALUES('history',?)", arrayOf(WireJson.encode(root - "records")))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized fun isDeleted(owner: String): Boolean = db.rawQuery("SELECT 1 FROM deleted WHERE owner=?", arrayOf(owner)).use { it.moveToFirst() }
    data class Cleanup(val owner: String, val kind: String, val error: String)
    @Synchronized fun pending(): List<Cleanup> = db.rawQuery("SELECT owner,kind,error FROM cleanup", null).use { cursor ->
        buildList { while (cursor.moveToNext()) add(Cleanup(cursor.getString(0), cursor.getString(1), cursor.getString(2))) }
    }
    @Synchronized fun enqueue(owner: String, kind: String) = enqueueInTransaction(owner, kind)
    private fun enqueueInTransaction(owner: String, kind: String) {
        db.execSQL("INSERT OR IGNORE INTO cleanup(owner,kind) VALUES(?,?)", arrayOf(owner, kind))
    }
    @Synchronized fun complete(job: Cleanup) { db.execSQL("DELETE FROM cleanup WHERE owner=? AND kind=?", arrayOf(job.owner, job.kind)) }
    @Synchronized fun failed(job: Cleanup) {
        db.execSQL("UPDATE cleanup SET error=? WHERE owner=? AND kind=?", arrayOf("未完成，稍后可重试", job.owner, job.kind))
    }
    data class Asset(val path: String, val kind: String, val owner: String, val idle: Boolean)
    @Synchronized fun assets(): List<Asset> = db.rawQuery("SELECT path,kind,owner,idle FROM assets", null).use { cursor ->
        buildList { while (cursor.moveToNext()) add(Asset(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getInt(3) != 0)) }
    }
    @Synchronized fun register(path: String, kind: String, owner: String, idle: Boolean = false) {
        db.execSQL("INSERT OR REPLACE INTO assets(path,kind,owner,idle) VALUES(?,?,?,?)", arrayOf<Any>(path, kind, owner, if (idle) 1 else 0))
    }
    @Synchronized fun forget(path: String) {
        db.execSQL("DELETE FROM asset_refs WHERE path=?", arrayOf(path))
        db.execSQL("DELETE FROM assets WHERE path=?", arrayOf(path))
    }
    @Synchronized fun references(path: String): Set<String> = db.rawQuery("SELECT DISTINCT owner FROM asset_refs WHERE path=?", arrayOf(path)).use { cursor ->
        buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }
    @Synchronized fun bind(path: String, owner: String, slot: String) {
        db.beginTransaction()
        try {
            db.execSQL("INSERT OR REPLACE INTO asset_refs(path,owner,slot) VALUES(?,?,?)", arrayOf(path, owner, slot))
            updateIdle()
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    @Synchronized fun unbind(owner: String, slot: String? = null) {
        db.beginTransaction()
        try {
            if (slot == null) db.execSQL("DELETE FROM asset_refs WHERE owner=?", arrayOf(owner))
            else db.execSQL("DELETE FROM asset_refs WHERE owner=? AND slot=?", arrayOf(owner, slot))
            updateIdle()
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    private fun updateIdle() { db.execSQL("UPDATE assets SET idle=CASE WHEN EXISTS(SELECT 1 FROM asset_refs r WHERE r.path=assets.path) THEN 0 ELSE 1 END WHERE kind='reading'") }
}
