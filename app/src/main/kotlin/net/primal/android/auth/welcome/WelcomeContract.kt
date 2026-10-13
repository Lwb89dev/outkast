package net.primal.android.auth.welcome

interface WelcomeContract {
    data class ScreenCallbacks(
        val onGetStartedClick: () -> Unit,
        val onExistingIdentityClick: () -> Unit,
    )
}
