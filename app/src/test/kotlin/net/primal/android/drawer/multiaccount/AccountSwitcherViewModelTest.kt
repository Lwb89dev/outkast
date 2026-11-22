package net.primal.android.drawer.multiaccount

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.primal.android.user.accounts.UserAccountsStore
import net.primal.android.user.accounts.active.ActiveAccountStore
import net.primal.android.user.domain.UserAccount
import net.primal.android.user.repository.UserRepository
import net.primal.core.testing.CoroutinesTestRule
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountSwitcherViewModelTest {

    @get:Rule
    val coroutineTestRule = CoroutinesTestRule()

    private val accountA = UserAccount.buildLocal(pubkey = "a".repeat(64)).copy(
        authorDisplayName = "Alice",
        lastAccessedAt = 300L,
    )
    private val accountB = UserAccount.buildLocal(pubkey = "b".repeat(64)).copy(
        authorDisplayName = "Bob",
        lastAccessedAt = 200L,
    )
    private val accountC = UserAccount.buildLocal(pubkey = "c".repeat(64)).copy(
        authorDisplayName = "Carol",
        lastAccessedAt = 100L,
    )

    @Test
    fun `switching active account preserves previously active account in other accounts list`() =
        runTest {
            val activeFlow = MutableStateFlow(accountA)
            val accountsFlow = MutableStateFlow(listOf(accountA, accountB, accountC))
            val activeAccountStore = mockk<ActiveAccountStore>(relaxed = true) {
                every { activeUserAccount } returns activeFlow
                every { activeUserId() } answers { activeFlow.value.pubkey }
            }
            val accountsStore = mockk<UserAccountsStore>(relaxed = true) {
                every { userAccounts } returns accountsFlow
            }
            val userRepository = mockk<UserRepository>(relaxed = true)

            val viewModel = AccountSwitcherViewModel(
                userRepository = userRepository,
                activeAccountStore = activeAccountStore,
                accountsStore = accountsStore,
            )
            advanceUntilIdle()

            viewModel.state.value.activeAccount?.pubkey shouldBe accountA.pubkey
            viewModel.state.value.userAccounts.map { it.pubkey } shouldContainExactly listOf(
                accountB.pubkey,
                accountC.pubkey,
            )

            // Simulate active account switching to B without accountsFlow emitting again first.
            activeFlow.value = accountB
            advanceUntilIdle()

            viewModel.state.value.activeAccount?.pubkey shouldBe accountB.pubkey
            viewModel.state.value.userAccounts.map { it.pubkey } shouldContainExactly listOf(
                accountA.pubkey,
                accountC.pubkey,
            )
        }

    @Test
    fun `switching to already active account is a no-op`() =
        runTest {
            val activeFlow = MutableStateFlow(accountA)
            val accountsFlow = MutableStateFlow(listOf(accountA, accountB))
            val activeAccountStore = mockk<ActiveAccountStore>(relaxed = true) {
                every { activeUserAccount } returns activeFlow
                every { activeUserId() } answers { activeFlow.value.pubkey }
            }
            val accountsStore = mockk<UserAccountsStore>(relaxed = true) {
                every { userAccounts } returns accountsFlow
            }
            val userRepository = mockk<UserRepository>(relaxed = true)

            val viewModel = AccountSwitcherViewModel(
                userRepository = userRepository,
                activeAccountStore = activeAccountStore,
                accountsStore = accountsStore,
            )
            advanceUntilIdle()

            viewModel.setEvent(AccountSwitcherContract.UiEvent.SwitchAccount(accountA.pubkey))
            advanceUntilIdle()

            coVerify(exactly = 0) { userRepository.setActiveAccount(any()) }
        }

    @Test
    fun `switching to another account updates repository and emits side effect`() =
        runTest {
            val activeFlow = MutableStateFlow(accountA)
            val accountsFlow = MutableStateFlow(listOf(accountA, accountB))
            val activeAccountStore = mockk<ActiveAccountStore>(relaxed = true) {
                every { activeUserAccount } returns activeFlow
                every { activeUserId() } answers { activeFlow.value.pubkey }
            }
            val accountsStore = mockk<UserAccountsStore>(relaxed = true) {
                every { userAccounts } returns accountsFlow
            }
            val userRepository = mockk<UserRepository>(relaxed = true)

            val viewModel = AccountSwitcherViewModel(
                userRepository = userRepository,
                activeAccountStore = activeAccountStore,
                accountsStore = accountsStore,
            )
            var effect: AccountSwitcherContract.SideEffect? = null
            val collectJob = launch { effect = viewModel.effects.first() }
            advanceUntilIdle()

            viewModel.setEvent(AccountSwitcherContract.UiEvent.SwitchAccount(accountB.pubkey))
            advanceUntilIdle()
            collectJob.join()

            coVerify(exactly = 1) { userRepository.setActiveAccount(accountB.pubkey) }
            effect shouldBe AccountSwitcherContract.SideEffect.AccountSwitched
        }
}
