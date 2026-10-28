package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.ChapterProgress
import eu.kanade.tachiyomi.source.model.SManga

/**
 * Compile-time stub matching Mihon/source-api ProgressAwareSource.
 * Provided as compileOnly so the extension APK does not package it; the host
 * supplies the real interface at runtime.
 */
interface ProgressAwareSource {
    suspend fun updateProgress(manga: SManga, progress: List<ChapterProgress>) {}
    suspend fun getProgress(manga: SManga, chapterUrls: List<String>): List<ChapterProgress> =
        emptyList()
    suspend fun getProgressSnapshot(manga: SManga): Map<String, ChapterProgress>? = null
}
