package net.primal.android.core.compose

/**
 * Stable destination identifiers retained for saved state and deep-link compatibility.
 * OUTKAST presents the primary destinations through its gesture orbit, not a navigation bar.
 */
enum class PrimalTopLevelDestination {
    Feeds,
    Reads,
    Messages,
    Alerts,
    Explore,
    You,
    Settings,
}
