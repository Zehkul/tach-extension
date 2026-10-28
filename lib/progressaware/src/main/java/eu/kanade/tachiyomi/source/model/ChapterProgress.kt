package eu.kanade.tachiyomi.source.model

/**
 * Compile-time stub matching Mihon/source-api ChapterProgress.
 * Must not be packaged in the extension APK.
 */
data class ChapterProgress(
    val chapterUrl: String,
    val pageIndex: Int,
    val pageCount: Int? = null,
    val completed: Boolean = false,
    val readAt: Long = 0L,
)
