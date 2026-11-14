package com.lsa.app.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.migration.Migration
import com.lsa.app.ml.embedding.Quant
import com.lsa.app.ml.embedding.VectorMath

/** v1 stored one row per image with two float32 vectors; v2 stores items + int8 segments. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `items` (`itemId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sourceKey` TEXT NOT NULL, " +
                "`kind` TEXT NOT NULL, `uri` TEXT NOT NULL, `folderKey` TEXT NOT NULL, `displayName` TEXT NOT NULL, `mimeType` TEXT, " +
                "`sizeBytes` INTEGER NOT NULL, `modifiedAt` INTEGER NOT NULL, `title` TEXT, `durationMs` INTEGER, `pageCount` INTEGER, " +
                "`width` INTEGER NOT NULL, `height` INTEGER NOT NULL, `dateTaken` INTEGER, `latitude` REAL, `longitude` REAL, " +
                "`status` TEXT NOT NULL, `error` TEXT, `embeddingModelId` TEXT NOT NULL, `pipelineKey` TEXT NOT NULL, " +
                "`progress` INTEGER NOT NULL, `indexedAt` INTEGER NOT NULL)",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_items_sourceKey` ON `items` (`sourceKey`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_items_kind` ON `items` (`kind`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_items_folderKey` ON `items` (`folderKey`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `segments` (`segId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `itemId` INTEGER NOT NULL, " +
                "`type` TEXT NOT NULL, `ordinal` INTEGER NOT NULL, `startMs` INTEGER, `endMs` INTEGER, `page` INTEGER, `text` TEXT, " +
                "`embedding` BLOB, `thumbPath` TEXT, FOREIGN KEY(`itemId`) REFERENCES `items`(`itemId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_segments_itemId` ON `segments` (`itemId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_segments_type` ON `segments` (`type`)")
        db.execSQL("ALTER TABLE `indexed_folders` ADD COLUMN `recursive` INTEGER NOT NULL DEFAULT 1")

        db.execSQL(
            """INSERT INTO items (sourceKey, kind, uri, folderKey, displayName, mimeType, sizeBytes, modifiedAt, title, durationMs,
                  pageCount, width, height, dateTaken, latitude, longitude, status, error, embeddingModelId, pipelineKey, progress, indexedAt)
               SELECT 'ms:image:' || mediaId, 'IMAGE', uri, relativePath, displayName, mimeType, sizeBytes, dateModified * 1000, NULL, NULL,
                  NULL, width, height, dateTaken, latitude, longitude, status, error, embeddingModelId,
                  embeddingModelId || '|' || COALESCE(ocrModelId, 'mlkit-ko') || '|c1', CASE WHEN status = 'DONE' THEN 1 ELSE 0 END, indexedAt
               FROM images""",
        )

        // Vectors change format (float32 -> int8), so they are converted row by row.
        db.query(
            """SELECT i.itemId, m.imageEmb, m.ocrEmb, m.ocrText FROM images m
               JOIN items i ON i.sourceKey = 'ms:image:' || m.mediaId WHERE m.status = 'DONE' AND m.imageEmb IS NOT NULL""",
        ).use { c ->
            while (c.moveToNext()) {
                val itemId = c.getLong(0)
                insertSegment(db, itemId, SegmentType.IMAGE, 0, null, Quant.encode(VectorMath.fromBytes(c.getBlob(1))))
                if (!c.isNull(2)) {
                    insertSegment(db, itemId, SegmentType.OCR_TEXT, 1, c.getString(3), Quant.encode(VectorMath.fromBytes(c.getBlob(2))))
                }
            }
        }
        db.execSQL("DROP TABLE images")
    }

    private fun insertSegment(db: SupportSQLiteDatabase, itemId: Long, type: SegmentType, ordinal: Int, text: String?, emb: ByteArray) {
        val stmt = db.compileStatement(
            "INSERT INTO segments (itemId, type, ordinal, startMs, endMs, page, text, embedding, thumbPath) VALUES (?, ?, ?, NULL, NULL, NULL, ?, ?, NULL)",
        )
        stmt.bindLong(1, itemId); stmt.bindString(2, type.name); stmt.bindLong(3, ordinal.toLong())
        if (text == null) stmt.bindNull(4) else stmt.bindString(4, text)
        stmt.bindBlob(5, emb)
        stmt.executeInsert()
        stmt.close()
    }
}

/** v3 adds searchable place names derived from the GPS position (filled by IndexRunner.sync for existing rows). */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `items` ADD COLUMN `place` TEXT")
    }
}

/** v4 adds chat sessions and their messages. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `chat_sessions` (`sessionId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, " +
                "`enabledTools` TEXT NOT NULL, `autoSearch` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `chat_messages` (`msgId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` INTEGER NOT NULL, " +
                "`role` TEXT NOT NULL, `text` TEXT NOT NULL, `toolName` TEXT, `toolArgs` TEXT, `attachments` TEXT, `createdAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`sessionId`) REFERENCES `chat_sessions`(`sessionId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_messages_sessionId` ON `chat_messages` (`sessionId`)")
    }
}

/** v5 adds pinned chats. */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `chat_sessions` ADD COLUMN `pinned` INTEGER NOT NULL DEFAULT 0")
    }
}

/** v6 adds scheduled chat tasks and links chat messages to them. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `chat_messages` ADD COLUMN `taskId` INTEGER")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `scheduled_tasks` (`taskId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` INTEGER NOT NULL, " +
                "`trigger` TEXT NOT NULL, `status` TEXT NOT NULL, `instruction` TEXT NOT NULL, `repeat` TEXT NOT NULL, `atMinuteOfDay` INTEGER NOT NULL, " +
                "`weekdayMask` INTEGER NOT NULL, `intervalMinutes` INTEGER NOT NULL, `onceAtMs` INTEGER, `nextRunAt` INTEGER, `fileQuery` TEXT NOT NULL, " +
                "`fileKinds` TEXT NOT NULL, `pendingItemIds` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `activatedAt` INTEGER, `lastRunAt` INTEGER, " +
                "`lastResult` TEXT, `lastError` TEXT, " +
                "FOREIGN KEY(`sessionId`) REFERENCES `chat_sessions`(`sessionId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_scheduled_tasks_sessionId` ON `scheduled_tasks` (`sessionId`)")
    }
}

/** v7: action cards (calendar event, contact, ...) the model proposes and the user approves in the chat. */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `chat_messages` ADD COLUMN `actionId` INTEGER")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `pending_actions` (`actionId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` INTEGER NOT NULL, " +
                "`kind` TEXT NOT NULL, `status` TEXT NOT NULL, `payload` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `resultNote` TEXT, " +
                "FOREIGN KEY(`sessionId`) REFERENCES `chat_sessions`(`sessionId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_pending_actions_sessionId` ON `pending_actions` (`sessionId`)")
    }
}

/** 0.9.0: faces and people (face grouping), items.faceKey (which photos were scanned for faces). */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `items` ADD COLUMN `faceKey` TEXT")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `people` (`personId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT, " +
                "`coverFaceId` INTEGER, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `faces` (`faceId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `itemId` INTEGER NOT NULL, " +
                "`left` REAL NOT NULL, `top` REAL NOT NULL, `right` REAL NOT NULL, `bottom` REAL NOT NULL, `score` REAL NOT NULL, " +
                "`vector` BLOB NOT NULL, `personId` INTEGER, `blockedPersonId` INTEGER, `thumbPath` TEXT, " +
                "FOREIGN KEY(`itemId`) REFERENCES `items`(`itemId`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`personId`) REFERENCES `people`(`personId`) ON UPDATE NO ACTION ON DELETE SET NULL )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_faces_itemId` ON `faces` (`itemId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_faces_personId` ON `faces` (`personId`)")
    }
}

/**
 * Called by [MIGRATION_8_9] with the ids of the scheduled chat tasks it drops, so their alarms can be cancelled
 * (the container sets it before opening the database).
 */
@Volatile var onChatTasksDropped: ((List<Long>) -> Unit)? = null

/**
 * 0.10.0: the chat moved to the Malbotdari app. Its tables go (the user chose not to carry chats over); the index,
 * folders and people stay.
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val ids = ArrayList<Long>()
        runCatching { db.query("SELECT taskId FROM scheduled_tasks").use { c -> while (c.moveToNext()) ids += c.getLong(0) } }
        // children first: messages, tasks and cards reference the sessions
        listOf("pending_actions", "scheduled_tasks", "chat_messages", "chat_sessions").forEach { db.execSQL("DROP TABLE IF EXISTS `$it`") }
        if (ids.isNotEmpty()) runCatching { onChatTasksDropped?.invoke(ids) }
    }
}

/** 0.13.0: the user's own search keywords ("My keywords"), kept per file (sourceKey) so they survive reindexing. */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `user_keywords` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sourceKey` TEXT NOT NULL, " +
                "`text` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_user_keywords_sourceKey_text` ON `user_keywords` (`sourceKey`, `text`)")
    }
}

/** Every migration, in order. Register new ones here only; the chain test in MigrationTest walks all exported schemas. */
val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
