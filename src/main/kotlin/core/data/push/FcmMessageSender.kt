package com.mozgobolt.core.data.push

import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message

/**
 * The one seam between [FcmPushNotificationSender] and the firebase-admin SDK's actual network
 * call — [FirebaseMessaging] is a final, effectively unmockable class, so tests substitute a fake
 * [FcmMessageSender] instead of hitting a real Firebase project, the same role
 * `FcmHttpTransport` played for the hand-rolled HTTP client this replaces.
 */
fun interface FcmMessageSender {
    /** Returns FCM's message id on success; throws [com.google.firebase.messaging.FirebaseMessagingException]
     * on failure, exactly as [FirebaseMessaging.send] does. */
    fun send(message: Message): String
}

class FirebaseFcmMessageSender(
    private val app: FirebaseApp,
) : FcmMessageSender {
    override fun send(message: Message): String = FirebaseMessaging.getInstance(app).send(message)
}
