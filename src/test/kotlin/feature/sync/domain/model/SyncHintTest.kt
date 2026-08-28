package com.shelflife.feature.sync.domain.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncHintTest {
    @Test
    fun `a hint is my own action when the device ids match`() {
        val hint = SyncHint(eventId = 1, originDeviceId = "device-A")

        assertTrue(hint.isOwnAction("device-A"))
    }

    @Test
    fun `a hint is not my own action when the device ids differ`() {
        val hint = SyncHint(eventId = 1, originDeviceId = "device-A")

        assertFalse(hint.isOwnAction("device-B"))
    }

    @Test
    fun `a hint with no origin device id is never anyone's own action`() {
        val hint = SyncHint(eventId = 1, originDeviceId = null)

        assertFalse(hint.isOwnAction("device-A"))
    }

    @Test
    fun `a subscriber with no device id of its own never suppresses anything`() {
        val hint = SyncHint(eventId = 1, originDeviceId = "device-A")

        assertFalse(hint.isOwnAction(null))
    }

    @Test
    fun `neither side having a device id is still not treated as anyone's own action`() {
        val hint = SyncHint(eventId = 1, originDeviceId = null)

        assertFalse(hint.isOwnAction(null))
    }
}
