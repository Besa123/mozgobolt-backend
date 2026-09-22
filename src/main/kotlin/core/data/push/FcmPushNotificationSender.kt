package com.mozgobolt.core.data.push

import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.MessagingErrorCode
import com.mozgobolt.core.domain.push.PushNotificationSender
import com.mozgobolt.core.utility.functions.runSuspendCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val INVALID_TARGET_CODES = setOf(MessagingErrorCode.UNREGISTERED, MessagingErrorCode.SENDER_ID_MISMATCH)
private val TRANSIENT_CODES =
    setOf(MessagingErrorCode.UNAVAILABLE, MessagingErrorCode.INTERNAL, MessagingErrorCode.QUOTA_EXCEEDED)

// A data-only message (no Notification block) isn't prioritized by FCM the way a notification
// message is by default — both this app's push events (a buyer's ping, a vehicle entering a
// watched area) are time-sensitive, so every send asks for high-priority delivery explicitly.
// One immutable config, built once: AndroidConfig.Builder has no mutable state worth
// re-allocating per call.
private val HIGH_PRIORITY_ANDROID_CONFIG = AndroidConfig.builder().setPriority(AndroidConfig.Priority.HIGH).build()

/**
 * The real FCM sender, backed by the official firebase-admin SDK (see [FcmMessageSender] for why
 * the actual [com.google.firebase.messaging.FirebaseMessaging.send] call sits behind a seam) —
 * the SDK owns the OAuth2 service-account token exchange and the HTTP transport internally, so
 * this class only builds the message and classifies whatever [FirebaseMessagingException] comes
 * back into this codebase's own [PushDeliveryException] hierarchy, exactly like
 * [com.mozgobolt.core.data.email.ResendEmailService] does for its SDK.
 *
 * Targets a Firebase Installation ID (FID) via [Message.Builder.setFid], not the older FCM
 * registration-token API ([Message.Builder.setToken], deprecated in firebase-admin 9.10.0 in
 * favor of `fid`) — this project adopted the FID model from the start since it had no released
 * client yet to migrate.
 *
 * Sends a pure FCM *data* message — never a [com.google.firebase.messaging.Notification] block —
 * so the OS never renders anything on the backend's behalf; the client receives the data payload
 * in its own message handler and builds whatever it displays, per product decision (the backend
 * sends structured facts, never copy).
 *
 * Honesty check, unchanged from the hand-rolled version this replaced: never exercised against a
 * real Firebase project in this session — no live credentials available here.
 */
class FcmPushNotificationSender(
    private val messageSender: FcmMessageSender,
) : PushNotificationSender {
    override suspend fun send(
        installationId: String,
        data: Map<String, String>,
    ) {
        val message =
            Message
                .builder()
                .setFid(installationId)
                .putAllData(data)
                .setAndroidConfig(HIGH_PRIORITY_ANDROID_CONFIG)
                .build()

        withContext(Dispatchers.IO) { runSuspendCatching { messageSender.send(message) } }
            .getOrElse { throw it.toPushDeliveryException() }
    }
}

private fun Throwable.toPushDeliveryException(): PushDeliveryException =
    when (this) {
        is FirebaseMessagingException -> messagingErrorCode.toPushDeliveryException(message ?: toString(), this)
        else -> TransientPushDeliveryException("Failure sending FCM push via firebase-admin", this)
    }

internal fun MessagingErrorCode?.toPushDeliveryException(
    message: String,
    cause: Throwable? = null,
): PushDeliveryException =
    when (this) {
        in INVALID_TARGET_CODES -> InvalidPushTargetException("FCM reported installation as $this: $message", cause)
        in TRANSIENT_CODES -> TransientPushDeliveryException("FCM send failed transiently ($this): $message", cause)
        else -> PermanentPushDeliveryException("FCM send failed permanently ($this): $message", cause)
    }
