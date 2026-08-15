package com.aischeck.app

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/** Result of a lookup. */
enum class MatchStatus { BLOCK, WARN, CLEAR }

data class Lists(
    val block: Set<String>,
    val warn: Set<String>,
    val lastModified: String?,
    val fromCache: Boolean
)

/**
 * Downloads + caches the AiSList blocklist / warnlist.
 * Entries are stored lower-cased for case-insensitive matching.
 */
object ListRepository {

    private const val BLOCK_URL =
        "https://raw.githubusercontent.com/Override92/AiSList/main/AiSList/aislist_blocklist.txt"
    private const val WARN_URL =
        "https://raw.githubusercontent.com/Override92/AiSList/main/AiSList/aislist_warnlist.txt"

    private const val BLOCK_CACHE = "blocklist.txt"
    private const val WARN_CACHE = "warnlist.txt"

    /**
     * Load lists. Tries the network first; on any failure falls back to the
     * on-disk cache. Only overwrites the cache when a download succeeds.
     */
    fun load(context: Context): Lists {
        val blockCache = File(context.filesDir, BLOCK_CACHE)
        val warnCache = File(context.filesDir, WARN_CACHE)

        var fromCache = false

        val blockText = fetch(BLOCK_URL)?.also { blockCache.writeText(it) }
            ?: blockCache.takeIf { it.exists() }?.readText()?.also { fromCache = true }
            ?: ""

        val warnText = fetch(WARN_URL)?.also { warnCache.writeText(it) }
            ?: warnCache.takeIf { it.exists() }?.readText()?.also { fromCache = true }
            ?: ""

        return Lists(
            block = parse(blockText),
            warn = parse(warnText),
            lastModified = extractLastModified(blockText),
            fromCache = fromCache
        )
    }

    private fun fetch(url: String): String? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            requestMethod = "GET"
        }
        if (conn.responseCode == 200) conn.inputStream.bufferedReader().use { it.readText() }
        else null
    } catch (e: Exception) {
        null
    }

    /** Parse a list file into a lower-cased set of normalised keys. */
    private fun parse(text: String): Set<String> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("!") }
            .map { normalizeKey(it) }
            .filter { it.isNotEmpty() }
            .toSet()

    private fun extractLastModified(text: String): String? =
        text.lineSequence()
            .firstOrNull { it.startsWith("! Last Modified:") }
            ?.substringAfter(":")
            ?.trim()

    /**
     * Normalise a stored entry to a comparison key.
     *  - @Handle       -> "@handle"
     *  - UCxxxx (24ch) -> "ucxxxx"
     */
    private fun normalizeKey(entry: String): String = entry.lowercase()

    /**
     * Turn arbitrary user input (a pasted URL, a shared link, or a bare
     * handle/name) into candidate keys to check against the lists.
     * Returns every plausible key so we don't miss a match.
     */
    fun candidatesFromInput(raw: String): List<String> {
        val input = raw.trim()
        if (input.isEmpty()) return emptyList()

        val out = mutableSetOf<String>()

        // Pull a URL out of shared text if there is one, else use the whole string.
        val urlMatch = Regex("https?://\\S+").find(input)?.value

        if (urlMatch != null) {
            out += candidatesFromUrl(urlMatch)
        }

        // Also treat the raw input as a possible bare handle / id / name.
        out += bareCandidates(input)

        // Some list entries are percent-encoded (e.g. "@%c3%89cho…" == "@Écho…").
        // Add decoded and encoded variants of every candidate so either form matches.
        val expanded = mutableSetOf<String>()
        for (c in out) {
            expanded += c
            runCatching { URLDecoder.decode(c, "UTF-8").lowercase() }.getOrNull()
                ?.let { expanded += it }
            runCatching {
                // Encode the part after a leading '@' so we don't escape the '@'.
                if (c.startsWith("@")) "@" + URLEncoder.encode(c.substring(1), "UTF-8")
                        .replace("+", "%20").lowercase()
                else URLEncoder.encode(c, "UTF-8").replace("+", "%20").lowercase()
            }.getOrNull()?.let { expanded += it }
        }

        return expanded.filter { it.isNotEmpty() }.distinct()
    }

    private fun candidatesFromUrl(url: String): List<String> {
        val out = mutableListOf<String>()
        // Strip protocol + query/fragment, keep the path.
        val cleaned = url
            .substringAfter("://", url)
            .substringBefore("?")
            .substringBefore("#")

        val path = cleaned.substringAfter("/", "")
        val segments = path.split("/").filter { it.isNotEmpty() }

        // youtube.com/@handle
        segments.firstOrNull { it.startsWith("@") }?.let {
            out += it.lowercase()
        }
        // youtube.com/channel/UCxxxx
        val chIdx = segments.indexOf("channel")
        if (chIdx >= 0 && chIdx + 1 < segments.size) {
            out += segments[chIdx + 1].lowercase()
        }
        // youtube.com/c/Name  or  youtube.com/user/Name  (legacy custom URLs)
        listOf("c", "user").forEach { key ->
            val i = segments.indexOf(key)
            if (i >= 0 && i + 1 < segments.size) {
                val name = segments[i + 1].lowercase()
                out += name
                out += "@$name"
            }
        }
        // Bare first segment that looks like a handle without the @, e.g. youtube.com/SomeName
        segments.firstOrNull()?.let { first ->
            if (first !in setOf("watch", "channel", "c", "user", "shorts", "playlist") &&
                !first.startsWith("@")
            ) {
                out += first.lowercase()
                out += "@${first.lowercase()}"
            }
        }
        return out
    }

    private fun bareCandidates(input: String): List<String> {
        val token = input.substringBefore(" ").trim()
        if (token.isEmpty() || token.startsWith("http")) return emptyList()
        val lower = token.lowercase()
        val out = mutableListOf(lower)
        if (!lower.startsWith("@")) out += "@$lower"
        return out
    }

    fun check(lists: Lists, input: String): MatchStatus {
        val keys = candidatesFromInput(input)
        if (keys.any { it in lists.block }) return MatchStatus.BLOCK
        if (keys.any { it in lists.warn }) return MatchStatus.WARN
        return MatchStatus.CLEAR
    }
}
