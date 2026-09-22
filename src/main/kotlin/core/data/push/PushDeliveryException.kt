package com.mozgobolt.core.data.push

sealed class PushDeliveryException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** Network error, FCM 429/5xx, or an OAuth-token-exchange failure of the same shape — worth
 * retrying. */
class TransientPushDeliveryException(
    message: String,
    cause: Throwable? = null,
) : PushDeliveryException(message, cause)

/** FCM reported the target installation as unregistered/invalid (`UNREGISTERED`/
 * `SENDER_ID_MISMATCH`) — retrying is pointless, and the caller should delete this installation
 * id rather than keep sending to it. */
class InvalidPushTargetException(
    message: String,
    cause: Throwable? = null,
) : PushDeliveryException(message, cause)

/** Anything else FCM rejected deterministically (bad request shape, auth misconfiguration) —
 * retrying would fail identically every time. */
class PermanentPushDeliveryException(
    message: String,
    cause: Throwable? = null,
) : PushDeliveryException(message, cause)
