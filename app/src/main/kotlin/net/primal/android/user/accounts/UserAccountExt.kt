package net.primal.android.user.accounts

import net.primal.android.user.domain.UserAccount

fun UserAccount.copyIfNotNull(
    profile: UserAccount?,
    stats: UserAccount?,
    followList: UserAccount?,
): UserAccount =
    this.copyProfileIfNotNull(profile = profile)
        .copyStatsIfNotNull(stats = stats)
        .copyFollowListIfNotNull(followList = followList)

fun UserAccount.copyProfileIfNotNull(profile: UserAccount?): UserAccount {
    return if (profile != null) {
        copy(
            authorDisplayName = profile.authorDisplayName,
            userDisplayName = profile.userDisplayName,
            avatarCdnImage = profile.avatarCdnImage,
            internetIdentifier = profile.internetIdentifier,
            lightningAddress = profile.lightningAddress,
            primalLegendProfile = profile.primalLegendProfile,
            blossomServers = profile.blossomServers,
        )
    } else {
        this
    }
}

fun UserAccount.copyFollowListIfNotNull(followList: UserAccount?): UserAccount {
    // A follow list is a replaceable event: the newest one wins everywhere. Relays that missed our
    // latest publish still serve the previous one, and adopting it here undid the user's last
    // follow or unfollow, which looked exactly like the button not working.
    val heldAt = followListCreatedAt
    val offeredAt = followList?.followListCreatedAt
    val isOlderThanHeld = heldAt != null && offeredAt != null && offeredAt < heldAt
    return if (followList == null || isOlderThanHeld) {
        this
    } else {
        copy(
            following = followList.following,
            interests = followList.interests,
            followListEventContent = followList.followListEventContent,
            followListCreatedAt = offeredAt ?: heldAt,
        )
    }
}

fun UserAccount.copyStatsIfNotNull(stats: UserAccount?): UserAccount {
    return if (stats != null) {
        copy(
            followersCount = stats.followersCount,
            followingCount = stats.followingCount,
            notesCount = stats.notesCount,
        )
    } else {
        this
    }
}
