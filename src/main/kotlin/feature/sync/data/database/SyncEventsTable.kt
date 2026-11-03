/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.sync.data.database

import com.mozgobolt.feature.user.data.database.UsersTable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp

object SyncEventsTable : Table("sync_events") {
    val id = long("id").autoIncrement()
    val userId =
        integer("user_id").references(
            UsersTable.id,
            onDelete = ReferenceOption.CASCADE,
            onUpdate = ReferenceOption.RESTRICT,
        )
    val entityType = varchar("entity_type", 30)
    val entityId = integer("entity_id")
    val operation = varchar("operation", 10)
    val occurredAt = timestamp("occurred_at")

    init {
        index(false, userId, id)
    }

    override val primaryKey = PrimaryKey(id)
}
