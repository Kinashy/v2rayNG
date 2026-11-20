package com.v2ray.ang.service

/** A selected-only VPN must not become a device-wide VPN when the selection is unavailable. */
internal fun configureSelectedApplications(
    enabled: Boolean,
    bypass: Boolean,
    selected: Set<String>,
    self: String,
    addAllowed: (String) -> Boolean,
    addDisallowed: (String) -> Boolean
): Boolean {
    if (!enabled) return addDisallowed(self)
    if (bypass) {
        (selected + self).forEach { addDisallowed(it) }
        return true
    }
    var allowed = 0
    (selected - self).forEach { if (addAllowed(it)) allowed++ }
    return allowed > 0
}
