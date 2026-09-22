package com.mozgobolt.core.data.push

import com.mozgobolt.core.domain.push.PUSH_DATA_KEY_TYPE
import com.mozgobolt.core.domain.push.PushNotificationSender
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Dev-only fallback when no FCM credentials are configured — mirrors
 * [com.mozgobolt.core.data.email.LoggingEmailService]. Never logs the installation id itself (it
 * is sensitive, same treatment as a refresh token) or the full data payload (may contain
 * user-identifying context), only the event's [com.mozgobolt.core.domain.push.PushEventType].
 */
class LoggingPushNotificationSender : PushNotificationSender {
    override suspend fun send(
        installationId: String,
        data: Map<String, String>,
    ) {
        logger.info { "Push notification not sent (no FCM credentials configured): type=${data[PUSH_DATA_KEY_TYPE]}" }
    }
}
