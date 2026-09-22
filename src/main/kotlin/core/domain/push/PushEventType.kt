package com.mozgobolt.core.domain.push

/**
 * Every push this backend can send, and the [String] keys its data payload carries. FCM data
 * messages are `Map<String, String>` ([PushNotificationSender.send]'s `data` parameter), so every
 * value below is already stringified by the caller before it reaches
 * [com.mozgobolt.core.data.push.FcmPushNotificationSender]. Deliberately not a generic key-value
 * framework — sized to the two events that exist today ([PING], [PROXIMITY_ALERT]); add a value
 * here when a third event is actually needed, not before.
 *
 * The backend never sends display text (title/body) — per product decision, the client owns all
 * notification copy and builds it from this structured payload.
 */
enum class PushEventType {
    PING,
    PROXIMITY_ALERT,
}

const val PUSH_DATA_KEY_TYPE = "type"
const val PUSH_DATA_KEY_VEHICLE_ID = "vehicleId"
const val PUSH_DATA_KEY_SENT_AT = "sentAt"
const val PUSH_DATA_KEY_COMPANY_ID = "companyId"
const val PUSH_DATA_KEY_SAVED_LOCATION_ID = "savedLocationId"
const val PUSH_DATA_KEY_SAVED_LOCATION_LABEL = "savedLocationLabel"
const val PUSH_DATA_KEY_LATITUDE = "latitude"
const val PUSH_DATA_KEY_LONGITUDE = "longitude"
