package net.primal.android.auth.login

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.primal.android.auth.login.LoginContract.UiEvent
import net.primal.android.auth.repository.LoginHandler
import net.primal.android.user.domain.CredentialType
import net.primal.core.testing.CoroutinesTestRule
import net.primal.domain.nostr.cryptography.utils.hexToNpubHrp
import net.primal.domain.profile.ProfileData
import net.primal.domain.profile.ProfileRepository
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {

    @get:Rule
    val coroutinesTestRule = CoroutinesTestRule()

    @Test
    fun validNpub_enablesPublicKeyLogin() =
        runTest {
            val viewModel = buildViewModel(profileRepository = mockk(relaxed = true))
            val npub = "npub1w0rthyjyp2f5gful0gm2500pwyxfrx93a85289xdz0sd6hyef33sh2cu4x"

            viewModel.setEvent(UiEvent.UpdateLoginInput(newInput = npub))
            advanceUntilIdle()

            viewModel.state.value.loginInput shouldBe npub
            viewModel.state.value.isValidKey shouldBe true
            viewModel.state.value.credentialType shouldBe CredentialType.PublicKey
        }

    @Test
    fun newerProfile_isNotOverwrittenByAnOlderRequest() =
        runTest {
            val firstUserId = "73c6bb92440a9344279f7a36aa3de1710c9198b1e9e8a394cd13e0dd5c994c63"
            val secondUserId = "88cc134b1a65f54ef48acc1df3665063d3ea45f04eab8af4646e561c5ae99079"
            val firstNpub = "npub1w0rthyjyp2f5gful0gm2500pwyxfrx93a85289xdz0sd6hyef33sh2cu4x"
            val secondNpub = secondUserId.hexToNpubHrp()
            val firstProfile = CompletableDeferred<ProfileData?>()
            val profileRepository = mockk<ProfileRepository>(relaxed = true) {
                coEvery { findProfileDataOrNull(firstUserId) } coAnswers { firstProfile.await() }
                coEvery { findProfileDataOrNull(secondUserId) } returns ProfileData(profileId = secondUserId)
            }
            val viewModel = buildViewModel(profileRepository)

            viewModel.setEvent(UiEvent.UpdateLoginInput(newInput = firstNpub))
            advanceUntilIdle()
            viewModel.setEvent(UiEvent.UpdateLoginInput(newInput = secondNpub))
            advanceUntilIdle()
            firstProfile.complete(ProfileData(profileId = firstUserId))
            advanceUntilIdle()

            viewModel.state.value.loginInput shouldBe secondNpub
            viewModel.state.value.profileDetails?.pubkey shouldBe secondUserId
            viewModel.state.value.fetchingProfileDetails shouldBe false
        }

    private fun buildViewModel(profileRepository: ProfileRepository) =
        LoginViewModel(
            dispatcherProvider = coroutinesTestRule.dispatcherProvider,
            profileRepository = profileRepository,
            loginHandler = mockk<LoginHandler>(relaxed = true),
        )
}
