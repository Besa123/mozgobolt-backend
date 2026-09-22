package com.mozgobolt.feature.sync.data.database

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp

object SyncEventsTable : Table("sync_events") {
    val id = long("id").autoIncrement()
    val userId = integer("user_id").index()
    val entityType = varchar("entity_type", 30)
    val entityId = integer("entity_id")
    val operation = varchar("operation", 10)
    val occurredAt = timestamp("occurred_at")

    override val primaryKey = PrimaryKey(id)
}
