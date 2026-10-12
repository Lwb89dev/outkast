package net.primal.android.core.errors

import android.content.Context
import net.primal.android.R
import net.primal.core.networking.blossom.BlossomException

private const val MAX_UPLOAD_FAILURE_DETAIL_LENGTH = 220

/**
 * "Unable to upload file" says nothing about whether to check the connection or to pick another
 * server. When the media servers answered, what they said (a refusal for a Tor address, a missing
 * upload permission) is shown after it, shortened so it stays a one-glance message.
 */
private fun resolveUploadFailureMessage(context: Context, cause: Throwable): String {
    val generic = context.getString(R.string.app_error_upload_failed)
    val detail = (cause as? BlossomException)?.message?.trim().orEmpty()
    if (detail.isEmpty()) return generic
    val shortened = if (detail.length > MAX_UPLOAD_FAILURE_DETAIL_LENGTH) {
        detail.take(MAX_UPLOAD_FAILURE_DETAIL_LENGTH).trimEnd() + "…"
    } else {
        detail
    }
    return context.getString(R.string.app_error_upload_failed_with_detail, generic, shortened)
}

@Suppress("CyclomaticComplexMethod")
fun UiError.resolveUiErrorMessage(context: Context): String {
    return when (this) {
        UiError.InvalidNaddr -> context.getString(
            R.string.app_error_invalid_naddr,
        )

        UiError.MissingPrivateKey -> context.getString(R.string.app_error_missing_private_key)


        is UiError.InvalidZapRequest -> context.getString(
            R.string.post_action_invalid_zap_request,
        )

        is UiError.MissingLightningAddress -> context.getString(
            R.string.post_action_missing_lightning_address,
        )

        is UiError.FailedToPublishZapEvent -> context.getString(
            R.string.post_action_zap_failed,
        )

        is UiError.FailedToPublishLikeEvent -> context.getString(
            R.string.post_action_like_failed,
        )

        is UiError.FailedToPublishDeleteEvent -> context.getString(R.string.post_action_delete_failed)

        is UiError.FailedToPublishRepostEvent -> context.getString(
            R.string.post_action_repost_failed,
        )

        is UiError.FailedToVotePoll -> context.getString(R.string.post_action_poll_vote_failed)

        is UiError.PollAlreadyVoted -> context.getString(R.string.post_action_poll_already_voted)

        is UiError.PollExpired -> context.getString(R.string.post_action_poll_expired)

        is UiError.PollAuthorCannotVote -> context.getString(R.string.post_action_poll_author_cannot_vote)

        is UiError.FailedToMuteUser -> context.getString(R.string.app_error_muting_user)

        is UiError.FailedToUnmuteUser -> context.getString(R.string.app_error_unmuting_user)

        is UiError.FailedToFollowMuteList -> context.getString(R.string.app_error_following_mute_list)

        is UiError.FailedToUpdateFollowList -> context.getString(R.string.app_error_unable_to_update_follow_list)

        is UiError.MissingRelaysConfiguration -> context.getString(
            R.string.app_missing_relays_config,
        )

        is UiError.FailedToAddToFeed -> context.getString(R.string.app_error_adding_to_feed)

        is UiError.GenericError -> context.getString(R.string.app_generic_error)

        is UiError.FailedToRemoveFeed -> context.getString(R.string.app_error_removing_feed)

        UiError.NostrSignUnauthorized -> context.getString(R.string.app_error_sign_unauthorized)

        is UiError.FailedToRestoreDefaultBlossomServer -> context.getString(
            R.string.app_error_restore_default_blossom_server,
        )

        is UiError.FailedToUpdateBlossomServer -> context.getString(
            R.string.app_error_unable_to_update_blossom_server_list,
        )

        is UiError.FailedToMuteThread -> context.getString(R.string.app_error_unmuting_thread)

        is UiError.FailedToUnmuteThread -> context.getString(R.string.app_error_unmuting_thread)

        is UiError.FailedToMuteHashtag -> context.getString(R.string.app_error_muting_hashtag)

        is UiError.FailedToMuteWord -> context.getString(R.string.app_error_muting_word)

        is UiError.FailedToUnmuteHashtag -> context.getString(R.string.app_error_unmuting_hashtag)

        is UiError.FailedToUnmuteWord -> context.getString(R.string.app_error_unmuting_word)

        is UiError.NetworkError -> context.getString(R.string.app_error_network)

        is UiError.SignatureError -> when (this.error) {
            SignatureUiError.SigningKeyNotFound -> context.getString(R.string.app_npub_login_error)
            SignatureUiError.SigningRejected -> context.getString(R.string.app_error_sign_unauthorized)
        }

        is UiError.FailedToFetchMuteList -> context.getString(R.string.app_error_fetching_mute_list)

        is UiError.FailedToUploadAttachment -> resolveUploadFailureMessage(context = context, cause = cause)

        UiError.GifSearchRateLimited -> context.getString(R.string.gif_picker_rate_limited)

        is UiError.PublishError -> context.getString(R.string.note_editor_nostr_publish_error)

        is UiError.InvalidPromoCode -> context.getString(R.string.app_error_invalid_promo_code)

        is UiError.FailedToBookmarkNote -> context.getString(R.string.app_error_bookmark_note)
    }
}
