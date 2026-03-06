/*
 * Copyright (c) 2025 Meshtastic LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.geeksville.mesh.database

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.DeleteTable
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.geeksville.mesh.database.dao.MeshLogDao
import com.geeksville.mesh.database.dao.NodeInfoDao
import com.geeksville.mesh.database.dao.PacketDao
import com.geeksville.mesh.database.dao.QuickChatActionDao
import com.geeksville.mesh.database.entity.ContactSettings
import com.geeksville.mesh.database.entity.MeshLog
import com.geeksville.mesh.database.entity.MetadataEntity
import com.geeksville.mesh.database.entity.MyNodeEntity
import com.geeksville.mesh.database.entity.NodeEntity
import com.geeksville.mesh.database.entity.Packet
import com.geeksville.mesh.database.entity.QuickChatAction
import com.geeksville.mesh.database.entity.ReactionEntity
import com.geeksville.mesh.plannedmessages.data.PlannedMessageDao
import com.geeksville.mesh.plannedmessages.data.PlannedMessageEntity

@Database(
    entities = [
        MyNodeEntity::class,
        NodeEntity::class,
        Packet::class,
        ContactSettings::class,
        MeshLog::class,
        QuickChatAction::class,
        ReactionEntity::class,
        MetadataEntity::class,
        PlannedMessageEntity::class,
    ],
    autoMigrations = [
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9),
        AutoMigration(from = 9, to = 10),
        AutoMigration(from = 10, to = 11),
        AutoMigration(from = 11, to = 12),
        AutoMigration(from = 12, to = 13, spec = AutoMigration12to13::class),
        AutoMigration(from = 13, to = 14),
        AutoMigration(from = 14, to = 15),
        AutoMigration(from = 15, to = 16),
        AutoMigration(from = 16, to = 17),
    ],
    version = 19,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class MeshtasticDatabase : RoomDatabase() {
    abstract fun nodeInfoDao(): NodeInfoDao
    abstract fun packetDao(): PacketDao
    abstract fun meshLogDao(): MeshLogDao
    abstract fun quickChatActionDao(): QuickChatActionDao
    abstract fun plannedMessageDao(): PlannedMessageDao

    companion object {
        fun getDatabase(context: Context): MeshtasticDatabase {

            return Room.databaseBuilder(
                context.applicationContext,
                MeshtasticDatabase::class.java,
                "meshtastic_database"
            )
                .addMigrations(MIGRATION_17_19, MIGRATION_18_19)
                .fallbackToDestructiveMigration()
                .build()
        }

        val MIGRATION_17_19: Migration = object : Migration(17, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS planned_messages (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        destination_key TEXT NOT NULL,
                        message_text TEXT NOT NULL,
                        schedule_type TEXT NOT NULL,
                        days_of_week_mask INTEGER NOT NULL,
                        hour_of_day INTEGER NOT NULL,
                        minute_of_hour INTEGER NOT NULL,
                        one_shot_at_utc_epoch_ms INTEGER,
                        timezone_id TEXT NOT NULL,
                        delivery_policy TEXT NOT NULL,
                        is_enabled INTEGER NOT NULL,
                        next_trigger_at_utc_epoch_ms INTEGER,
                        in_flight_until_utc_epoch_ms INTEGER,
                        last_attempted_at_utc_epoch_ms INTEGER,
                        attempt_count_since_last_fire INTEGER NOT NULL,
                        last_fired_at_utc_epoch_ms INTEGER,
                        had_timezone_fallback INTEGER NOT NULL,
                        created_at_utc_epoch_ms INTEGER NOT NULL,
                        updated_at_utc_epoch_ms INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_planned_messages_destination_key
                    ON planned_messages(destination_key)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_planned_messages_is_enabled_next_trigger_at_utc_epoch_ms_in_flight_until_utc_epoch_ms
                    ON planned_messages(is_enabled, next_trigger_at_utc_epoch_ms, in_flight_until_utc_epoch_ms)
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_18_19: Migration = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    ALTER TABLE planned_messages
                    ADD COLUMN in_flight_until_utc_epoch_ms INTEGER
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    ALTER TABLE planned_messages
                    ADD COLUMN last_attempted_at_utc_epoch_ms INTEGER
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    ALTER TABLE planned_messages
                    ADD COLUMN attempt_count_since_last_fire INTEGER NOT NULL DEFAULT 0
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    ALTER TABLE planned_messages
                    ADD COLUMN had_timezone_fallback INTEGER NOT NULL DEFAULT 0
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_planned_messages_is_enabled_next_trigger_at_utc_epoch_ms_in_flight_until_utc_epoch_ms
                    ON planned_messages(is_enabled, next_trigger_at_utc_epoch_ms, in_flight_until_utc_epoch_ms)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    DROP INDEX IF EXISTS index_planned_messages_is_enabled_next_trigger_at_utc_epoch_ms
                    """.trimIndent()
                )
            }
        }
    }
}

@DeleteTable.Entries(
    DeleteTable(tableName = "NodeInfo"),
    DeleteTable(tableName = "MyNodeInfo")
)
class AutoMigration12to13 : AutoMigrationSpec
