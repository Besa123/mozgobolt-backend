/*
 * SPDX-License-Identifier: Apache-2.0
 */

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
    /** A buyer's transient request for a vehicle's attention. */
    PING,

    /** A vehicle entered a buyer's saved-location radius. */
    PROXIMITY_ALERT,
}

/** Data-payload key containing the [PushEventType] value. */
const val PUSH_DATA_KEY_TYPE = "type"

/** Data-payload key containing the vehicle identifier. */
const val PUSH_DATA_KEY_VEHICLE_ID = "vehicleId"

/** Data-payload key containing the event timestamp. */
const val PUSH_DATA_KEY_SENT_AT = "sentAt"

/** Data-payload key containing the company identifier. */
const val PUSH_DATA_KEY_COMPANY_ID = "companyId"

/** Data-payload key containing the saved-location identifier. */
const val PUSH_DATA_KEY_SAVED_LOCATION_ID = "savedLocationId"

/** Data-payload key containing the saved-location label. */
const val PUSH_DATA_KEY_SAVED_LOCATION_LABEL = "savedLocationLabel"

/** Data-payload key containing the event latitude. */
const val PUSH_DATA_KEY_LATITUDE = "latitude"

/** Data-payload key containing the event longitude. */
const val PUSH_DATA_KEY_LONGITUDE = "longitude"
