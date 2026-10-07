package net.primal.data.repository.fetch

/**
 * No relay answered a request that was meant to page through history.
 *
 * Distinct from an empty answer. Relays that reply "nothing older" mean the history has ended; a
 * pool that is offline, backing off or timing out says nothing at all, and a pager that took the
 * silence for an end stopped loading older notes for good after one dropped connection. Callers
 * that page backwards catch this and leave the cursor where it was so the next scroll retries.
 */
internal class RelaysUnreachableException : RuntimeException("No relay answered the request.")
