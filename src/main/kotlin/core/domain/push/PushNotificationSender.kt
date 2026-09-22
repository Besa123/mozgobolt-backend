package com.mozgobolt.core.domain.push

/**
 * One device push notification (FCM today; [com.mozgobolt.feature.deviceInstallation.domain.model.DevicePlatform]
 * already models iOS too, even though only Android is wired up client-side so far). [send] throws
 * on failure rather than returning an [com.mozgobolt.core.domain.AppResult] — the caller needs to
 * distinguish "retry" from "this installation is dead, stop trying" from "something else is
 * wrong," which this codebase's other external-call boundaries
 * ([com.mozgobolt.core.domain.email.EmailService]) already model as a small exception hierarchy
 * for exactly that reason.
 *
 * [data] is the entire payload — this backend never sends display text; see [PushEventType] for
 * the keys/values a caller builds it from.
 */
interface PushNotificationSender {
    suspend fun send(
        installationId: String,
        data: Map<String, String>,
    )
}
