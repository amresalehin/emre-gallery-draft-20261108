package com.amresalehin.emregallery.face

import android.database.sqlite.SQLiteDatabase

/**
 * Encapsulates table names, DDL creation statements, and version migrations
 * for the face recognition SQLite database.
 */
object FaceDbSchema {

    const val DATABASE_NAME = "face_index.db"
    const val DATABASE_VERSION = 4

    const val TABLE_PEOPLE = "people"
    const val TABLE_CLUSTERS = "clusters"
    const val TABLE_FACES = "faces"
    const val TABLE_INDEXED_MEDIA = "indexed_media"
    const val TABLE_FACE_FEEDBACK = "face_feedback"
    const val TABLE_REVIEW_SUGGESTIONS = "review_suggestions"
    const val TABLE_FACE_METRICS_V2 = "face_metrics_v2"
    const val TABLE_GROUPING_STATE = "grouping_state"

    fun createAll(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE people (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL DEFAULT '',
                cover_face_id TEXT,
                cover_media_uri TEXT,
                is_hidden INTEGER NOT NULL DEFAULT 0,
                engine_id TEXT NOT NULL DEFAULT 'buffalo_l',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX idx_people_engine ON people(engine_id)")

        db.execSQL(
            """CREATE TABLE clusters (
                id TEXT PRIMARY KEY,
                person_id TEXT,
                display_name TEXT NOT NULL,
                centroid BLOB NOT NULL,
                embedding_dim INTEGER NOT NULL DEFAULT 512,
                engine_id TEXT NOT NULL DEFAULT 'buffalo_l',
                model_version TEXT NOT NULL DEFAULT 'w600k_r50',
                face_count INTEGER NOT NULL DEFAULT 0,
                representative_face_id TEXT,
                created_at INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL,
                FOREIGN KEY(person_id) REFERENCES people(id) ON DELETE SET NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX idx_clusters_person ON clusters(person_id)")
        db.execSQL("CREATE INDEX idx_clusters_engine ON clusters(engine_id, model_version)")

        db.execSQL(
            """CREATE TABLE faces (
                media_uri TEXT NOT NULL,
                face_index INTEGER NOT NULL,
                engine_id TEXT NOT NULL DEFAULT 'buffalo_l',
                face_id TEXT,
                cluster_id TEXT,
                person_id TEXT,
                embedding BLOB NOT NULL,
                model_version TEXT NOT NULL DEFAULT 'w600k_r50',
                embedding_dim INTEGER NOT NULL DEFAULT 512,
                format_version INTEGER NOT NULL DEFAULT 1,
                box_left REAL NOT NULL DEFAULT 0.0,
                box_top REAL NOT NULL DEFAULT 0.0,
                box_right REAL NOT NULL DEFAULT 0.0,
                box_bottom REAL NOT NULL DEFAULT 0.0,
                detection_confidence REAL NOT NULL DEFAULT 1.0,
                quality_score REAL NOT NULL DEFAULT 1.0,
                created_at INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(media_uri, face_index, engine_id),
                FOREIGN KEY(cluster_id) REFERENCES clusters(id) ON DELETE SET NULL,
                FOREIGN KEY(person_id) REFERENCES people(id) ON DELETE SET NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX idx_faces_engine_cluster ON faces(engine_id, cluster_id)")
        db.execSQL("CREATE INDEX idx_faces_engine_person ON faces(engine_id, person_id)")
        db.execSQL("CREATE INDEX idx_faces_media ON faces(media_uri)")

        db.execSQL(
            """CREATE TABLE indexed_media (
                media_uri TEXT NOT NULL,
                engine_id TEXT NOT NULL DEFAULT 'buffalo_l',
                indexed_at INTEGER NOT NULL,
                face_count INTEGER NOT NULL,
                status TEXT NOT NULL,
                error_message TEXT,
                PRIMARY KEY(media_uri, engine_id)
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX idx_indexed_media_engine ON indexed_media(engine_id, status)")

        db.execSQL(
            """CREATE TABLE face_feedback (
                id TEXT PRIMARY KEY,
                feedback_type TEXT NOT NULL,
                source_id TEXT NOT NULL,
                target_id TEXT NOT NULL,
                engine_id TEXT NOT NULL DEFAULT 'buffalo_l',
                notes TEXT,
                created_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX idx_feedback_source ON face_feedback(source_id)")
        db.execSQL("CREATE INDEX idx_feedback_target ON face_feedback(target_id)")
        db.execSQL("CREATE INDEX idx_feedback_engine ON face_feedback(engine_id)")

        db.execSQL(
            """CREATE TABLE review_suggestions (
                id TEXT PRIMARY KEY,
                face_id TEXT NOT NULL,
                media_uri TEXT NOT NULL,
                face_index INTEGER NOT NULL,
                engine_id TEXT NOT NULL DEFAULT 'buffalo_l',
                suggested_person_id TEXT NOT NULL,
                distance REAL NOT NULL,
                status TEXT NOT NULL DEFAULT 'pending',
                created_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX idx_suggestions_face ON review_suggestions(face_id)")
        db.execSQL("CREATE INDEX idx_suggestions_person ON review_suggestions(suggested_person_id)")
        db.execSQL("CREATE INDEX idx_suggestions_engine ON review_suggestions(engine_id)")

        db.execSQL(
            """CREATE TABLE IF NOT EXISTS face_metrics_v2 (
                media_uri TEXT NOT NULL,
                face_index INTEGER NOT NULL,
                engine_id TEXT NOT NULL DEFAULT 'buffalo_l',
                blur_score REAL NOT NULL DEFAULT 1.0,
                pose_score REAL NOT NULL DEFAULT 1.0,
                size_ratio REAL NOT NULL DEFAULT 0.05,
                aspect_ratio REAL NOT NULL DEFAULT 0.85,
                overall_quality REAL NOT NULL DEFAULT 1.0,
                version INTEGER NOT NULL DEFAULT 1,
                created_at INTEGER NOT NULL,
                PRIMARY KEY(media_uri, face_index, engine_id)
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_face_metrics_engine ON face_metrics_v2(engine_id)")

        db.execSQL(
            """CREATE TABLE IF NOT EXISTS grouping_state (
                engine_id TEXT NOT NULL, model_version TEXT NOT NULL,
                strategy_version INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL DEFAULT 'pending',
                grouped_people INTEGER NOT NULL DEFAULT 0, face_count INTEGER NOT NULL DEFAULT 0,
                error_message TEXT, updated_at INTEGER NOT NULL,
                PRIMARY KEY(engine_id, model_version)
            )""".trimIndent()
        )
    }

    fun upgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS face_metrics_v2 (
                    media_uri TEXT NOT NULL,
                    face_index INTEGER NOT NULL,
                    engine_id TEXT NOT NULL DEFAULT 'buffalo_l',
                    blur_score REAL NOT NULL DEFAULT 1.0,
                    pose_score REAL NOT NULL DEFAULT 1.0,
                    size_ratio REAL NOT NULL DEFAULT 0.05,
                    aspect_ratio REAL NOT NULL DEFAULT 0.85,
                    overall_quality REAL NOT NULL DEFAULT 1.0,
                    version INTEGER NOT NULL DEFAULT 1,
                    created_at INTEGER NOT NULL,
                    PRIMARY KEY(media_uri, face_index, engine_id)
                )""".trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_face_metrics_engine ON face_metrics_v2(engine_id)")
        }
        if (oldVersion < 3) {
            runCatching {
                db.execSQL("ALTER TABLE indexed_media ADD COLUMN error_message TEXT")
            }
        }
        if (oldVersion < 4) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS grouping_state (
                    engine_id TEXT NOT NULL, model_version TEXT NOT NULL,
                    strategy_version INTEGER NOT NULL DEFAULT 0,
                    status TEXT NOT NULL DEFAULT 'pending',
                    grouped_people INTEGER NOT NULL DEFAULT 0, face_count INTEGER NOT NULL DEFAULT 0,
                    error_message TEXT, updated_at INTEGER NOT NULL,
                    PRIMARY KEY(engine_id, model_version)
                )""".trimIndent()
            )
        }
    }
}
