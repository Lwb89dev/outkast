package net.primal.android.feeds.algorithm

import net.primal.android.core.compose.profile.model.UserProfileItemUi

interface CreateAlgorithmContract {

    /** One of the user's own follow sets, offered for import. */
    data class ImportableList(
        val identifier: String,
        val title: String,
        val memberCount: Int,
        /** Already one of the user's algorithms, so there is nothing to add. */
        val added: Boolean = false,
    )

    data class UiState(
        val name: String = "",
        val query: String = "",
        /** Everyone the user follows, by name. */
        val contacts: List<UserProfileItemUi> = emptyList(),
        val loadingContacts: Boolean = true,
        /** People the search found who are not among [contacts]. */
        val searchResults: List<UserProfileItemUi> = emptyList(),
        val searching: Boolean = false,
        /** The chosen people by profile id, in the order they were chosen. */
        val selected: Map<String, UserProfileItemUi> = emptyMap(),
        val importableLists: List<ImportableList> = emptyList(),
        val saving: Boolean = false,
        val failed: Boolean = false,
        val created: Boolean = false,
    ) {
        val canCreate: Boolean get() = name.isNotBlank() && selected.isNotEmpty() && !saving

        /** The contacts the search box lets through: all of them when it is empty. */
        val visibleContacts: List<UserProfileItemUi>
            get() = if (query.isBlank()) contacts else contacts.filter { it.matches(query) }

        /** Chosen people who are not contacts, so they stay on screen once the search that found them is gone. */
        val selectedOutsideContacts: List<UserProfileItemUi>
            get() {
                val contactIds = contacts.mapTo(HashSet()) { it.profileId }
                return selected.values.filter { it.profileId !in contactIds }
            }
    }

    sealed class UiEvent {
        data class SetName(val name: String) : UiEvent()
        data class SetQuery(val query: String) : UiEvent()
        data class ToggleProfile(val profile: UserProfileItemUi) : UiEvent()
        data class ImportList(val list: ImportableList) : UiEvent()
        data object Create : UiEvent()
    }
}

/** Whether [query] is part of the person's name or internet identifier, ignoring case. */
internal fun UserProfileItemUi.matches(query: String): Boolean {
    val needle = query.trim()
    return displayName.contains(needle, ignoreCase = true) ||
        internetIdentifier?.contains(needle, ignoreCase = true) == true
}
