package com.mozgobolt.feature.deviceInstallation.domain.model

/**
 * Only Android is wired up client-side so far, but the domain model is platform-agnostic from
 * day one — adding iOS later should never require touching this enum's callers, only adding a
 * client.
 */
enum class DevicePlatform {
    ANDROID,
    IOS,
}
