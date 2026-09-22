package com.mozgobolt.core.data.push

import com.google.firebase.messaging.Message
import com.google.firebase.messaging.MessagingErrorCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * [FirebaseMessagingException][com.google.firebase.messaging.FirebaseMessagingException] has no
 * public constructor a test can use to fabricate a specific [MessagingErrorCode] — the SDK's own
 * issue tracker confirms this is a known, currently-unresolved encapsulation gap
 * (firebase/firebase-admin-java#695), not an oversight here. So the classification logic
 * ([toPushDeliveryException]) is tested directly against real [MessagingErrorCode] values —
 * that's the actual business logic — while [FcmPushNotificationSender.send]'s integration is
 * tested for the success path and the non-FCM-exception fallback path, the two cases a fake
 * [FcmMessageSender] genuinely can exercise.
 */
class FcmPushNotificationSenderTest {
    @Test
    fun `a successful send does not throw`() {
        runBlocking {
            val sender = FcmPushNotificationSender(FcmMessageSender { "message-id" })

            sender.send("device-fid", mapOf("type" to "PING"))
        }
    }

    @Test
    fun `send builds exactly one message and hands it to the message sender`() {
        // Message exposes no public getters to inspect after building (write-only builder, and
        // FirebaseMessagingException#695-style encapsulation) — the only thing verifiable from
        // outside the SDK is that send() reaches this seam exactly once per call.
        runBlocking {
            var callCount = 0
            val sender =
                FcmPushNotificationSender(
                    FcmMessageSender { message ->
                        callCount++
                        assertIs<Message>(message)
                        "id"
                    },
                )

            sender.send("device-fid", mapOf("type" to "PING", "vehicleId" to "10"))

            assertEquals(1, callCount)
        }
    }

    @Test
    fun `a non-FCM failure from the message sender is treated as transient`() {
        runBlocking {
            val sender = FcmMessageSender { throw IllegalStateException("network blew up") }

            assertFailsWith<TransientPushDeliveryException> {
                FcmPushNotificationSender(sender).send("device-fid", mapOf("type" to "PING"))
            }
        }
    }

    @Test
    fun `UNREGISTERED classifies as an invalid token, not retried`() {
        val result = MessagingErrorCode.UNREGISTERED.toPushDeliveryException("gone")
        assertIs<InvalidPushTargetException>(result)
    }

    @Test
    fun `SENDER_ID_MISMATCH also classifies as an invalid token`() {
        val result = MessagingErrorCode.SENDER_ID_MISMATCH.toPushDeliveryException("wrong sender")
        assertIs<InvalidPushTargetException>(result)
    }

    @Test
    fun `UNAVAILABLE classifies as transient`() {
        val result = MessagingErrorCode.UNAVAILABLE.toPushDeliveryException("down")
        assertIs<TransientPushDeliveryException>(result)
    }

    @Test
    fun `INTERNAL classifies as transient`() {
        val result = MessagingErrorCode.INTERNAL.toPushDeliveryException("oops")
        assertIs<TransientPushDeliveryException>(result)
    }

    @Test
    fun `QUOTA_EXCEEDED classifies as transient`() {
        val result = MessagingErrorCode.QUOTA_EXCEEDED.toPushDeliveryException("rate limited")
        assertIs<TransientPushDeliveryException>(result)
    }

    @Test
    fun `INVALID_ARGUMENT classifies as permanent, not retried`() {
        val result = MessagingErrorCode.INVALID_ARGUMENT.toPushDeliveryException("bad request")
        assertIs<PermanentPushDeliveryException>(result)
    }

    @Test
    fun `a null error code (no FCM classification available) falls back to permanent`() {
        val result = null.toPushDeliveryException("unclassified")
        assertIs<PermanentPushDeliveryException>(result)
    }
}
