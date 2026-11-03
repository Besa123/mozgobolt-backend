/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.data.push

import com.mozgobolt.core.domain.push.PushNotificationSender

class FakePushNotificationSender : PushNotificationSender {
    data class SentPush(
        val installationId: String,
        val data: Map<String, String>,
    )

    val sent = mutableListOf<SentPush>()
    private val throwForInstallationId = mutableMapOf<String, Throwable>()

    fun failNextSendTo(
        installationId: String,
        exception: Throwable,
    ) {
        throwForInstallationId[installationId] = exception
    }

    override suspend fun send(
        installationId: String,
        data: Map<String, String>,
    ) {
        throwForInstallationId.remove(installationId)?.let { throw it }
        sent += SentPush(installationId, data)
    }
}
