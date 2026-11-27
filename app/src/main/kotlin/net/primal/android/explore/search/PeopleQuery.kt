package net.primal.android.explore.search

private val TOKEN = Regex("-?[A-Za-z_]+:\"[^\"]*\"|-?\"[^\"]*\"|[^\\s()\"]+")
private const val MAX_NAME_WORDS = 3

/**
 * The part of a search that can name somebody, for the People tab and the suggestions:
 * "from:alice caffè" is about alice, "@bob" about bob, "#nostr" about nostr, plain words about
 * themselves. Exclusions and other operators say nothing about who is meant.
 */
internal fun String.toPeopleQuery(): String {
    val tokens = TOKEN.findAll(this).map { it.value }.toList()
    val named = tokens.firstNotNullOfOrNull { token ->
        when {
            token.startsWith("from:") || token.startsWith("to:") -> token.substringAfter(':')
            token.startsWith("@") -> token
            else -> null
        }
    }
    val text = named ?: tokens
        .filterNot { it == "OR" || it.startsWith("-") || (':' in it && !it.startsWith("\"")) }
        .take(MAX_NAME_WORDS)
        .joinToString(" ")
    return text.trim().trim('"').removePrefix("@").removePrefix("#")
}

/** A single bare word, worth also offering as a hashtag. */
internal fun String.asHashtagSuggestion(): String? {
    val word = trim()
    val bare = word.isNotEmpty() && word.all { it.isLetterOrDigit() || it == '_' }
    return if (bare) "#$word" else null
}
