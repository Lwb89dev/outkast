package net.primal.core.networking.blossom

open class BlossomException(message: String? = null, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * A server (or all of them) did not take an upload. [statusCode] is the HTTP status the server
 * answered with, when it answered at all, so a caller can tell "this server refuses" (401, 403)
 * from "this server has no such endpoint" (404, 405) and from a connection that never got through.
 */
class BlossomUploadException(
    message: String? = null,
    cause: Throwable? = null,
    val statusCode: Int? = null,
) : BlossomException(message, cause)

/**
 * A server's answer to the "would you take this?" pre-flight (BUD-06). [statusCode] is the HTTP status,
 * and the server's own words are in the message: "File size exceeds the maximum allowed size of 20 MiB".
 */
class UploadRequirementException(
    message: String? = null,
    cause: Throwable? = null,
    val statusCode: Int? = null,
) : BlossomException(message, cause)

class BlossomMirrorException(message: String? = null, cause: Throwable? = null) : BlossomException(message, cause)
