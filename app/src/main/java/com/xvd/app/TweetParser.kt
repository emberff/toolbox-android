package com.xvd.app

object TweetParser {

    data class TweetUrl(
        val username: String,
        val statusId: String,
        val original: String
    )

    private val STATUS_REGEX = Regex(
        """https?://(?:www\.|mobile\.|m\.)?(?:twitter\.com|x\.com)/(?:#!\/)?([A-Za-z0-9_]{1,20})/status(?:es)?/(\d{1,20})""",
        RegexOption.IGNORE_CASE
    )

    fun findFirst(text: String?): TweetUrl? {
        if (text.isNullOrBlank()) return null
        return STATUS_REGEX.find(text)?.let { m ->
            TweetUrl(m.groupValues[1], m.groupValues[2], m.value)
        }
    }
}
