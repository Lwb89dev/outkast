package net.primal.data.local.dao.wot

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index

/**
 * One pubkey in reach of a given owner's web of trust: followed by the owner directly, or by at
 * least one of the owner's follows. [followers] says by how many, so each use can choose how wide
 * its network is (see `WebOfTrustRepository`); a direct follow carries [Int.MAX_VALUE] and so is
 * in every width. The feed query joins against this table when web-of-trust filtering is active.
 */
@Entity(
    primaryKeys = ["ownerId", "pubkey"],
    indices = [Index(value = ["ownerId"])],
)
data class WotQualifiedPubkeyData(
    val ownerId: String,
    val pubkey: String,
    // Rows written before this column existed were all qualified at the feed's threshold of 10.
    @ColumnInfo(defaultValue = "10")
    val followers: Int = 10,
)
