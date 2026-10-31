package eu.kanade.tachiyomi.extension.all.kavita

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.extension.all.kavita.dto.ChapterDto
import eu.kanade.tachiyomi.extension.all.kavita.dto.ProgressDto
import eu.kanade.tachiyomi.extension.all.kavita.dto.SeriesDto
import eu.kanade.tachiyomi.extension.all.kavita.dto.VolumeDto
import eu.kanade.tachiyomi.source.ProgressAwareSource
import eu.kanade.tachiyomi.source.model.ChapterProgress
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Progress-aware Kavita for hosts that include [ProgressAwareSource].
 * Selected by [KavitaFactory] via Class.forName so old hosts never load this class.
 */
class KavitaWithProgress(suffix: String = "") :
    Kavita(suffix),
    ProgressAwareSource {

    private inline fun <reified T> okhttp3.Response.parseAs(): T {
        val body = body.string()
        if (body.isEmpty()) throw IOException("Empty response")
        return json.decodeFromString(kotlinx.serialization.serializer<T>(), body)
    }

    private fun parseReadingProgressUtc(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        return try {
            // Kavita omits zone and may use 7-digit fractional seconds.
            var s = value.trim().replace(' ', 'T')
            if (!s.endsWith("Z") && !s.contains('+') && s.count { it == '-' } <= 2) {
                s += "Z"
            }
            val dot = s.indexOf('.')
            if (dot >= 0) {
                val z = s.indexOf('Z', dot).let { if (it < 0) s.indexOf('+', dot) else it }
                val end = if (z < 0) s.length else z
                val frac = s.substring(dot + 1, end)
                if (frac.length > 9) {
                    s = s.substring(0, dot + 1) + frac.take(9) + s.substring(end)
                } else if (frac.length in 1..2) {
                    s = s.substring(0, dot + 1) + frac.padEnd(3, '0') + s.substring(end)
                }
            }
            java.time.Instant.parse(s).toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }

    override suspend fun updateProgress(
        manga: SManga,
        progress: List<ChapterProgress>,
    ): List<String> {
        if (progress.isEmpty()) return emptyList()
        val byUrl = progress.associateBy { it.chapterUrl }
        val resolved = linkedSetOf<String>()
        val seriesId = helper.getIdFromUrl(manga.url)

        val libraryId = try {
            seriesLibraryCache[seriesId] ?: run {
                client.newCall(GET("$apiUrl/Series/$seriesId", headersBuilder().build())).await().use { response ->
                    if (!response.isSuccessful) throw IOException("Series HTTP ${response.code}")
                    val seriesDto = response.parseAs<SeriesDto>()
                    seriesLibraryCache[seriesId] = seriesDto.libraryId
                    seriesDto.libraryId
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IOException("updateProgress series lookup failed", e)
        }

        val toMarkRead = linkedSetOf<Int>()
        val toMarkUnread = linkedSetOf<Int>()
        val pageUpdates = mutableListOf<ChapterProgress>()

        chapterLoop@ for (p in byUrl.values) {
            val chapterId = parseChapterId(p.chapterUrl)
            if (chapterId == null) {
                resolved += p.chapterUrl
                continue@chapterLoop
            }
            val tocStart = parseTocStart(p.chapterUrl)
            val isTocSlice = "tocStart=" in p.chapterUrl
            try {
                val meta: Pair<Int, Int>? =
                    chapterMetaCache[chapterId] ?: run {
                        client.newCall(GET("$apiUrl/Chapter?chapterId=$chapterId", headersBuilder().build())).await().use { response ->
                            if (!response.isSuccessful) {
                                if (response.code == 404) {
                                    resolved += p.chapterUrl
                                    return@run null
                                }
                                throw IOException("Chapter HTTP ${response.code}")
                            }
                            val chapterDetails = response.parseAs<ChapterDto>()
                            (chapterDetails.volumeId to chapterDetails.pages).also {
                                chapterMetaCache[chapterId] = it
                            }
                        }
                    }
                if (meta == null) continue@chapterLoop
                val pages = meta.second
                val absolutePage = tocStart + p.pageIndex
                val atChapterEnd = absolutePage >= (pages - 1).coerceAtLeast(0)

                when {
                    p.completed && (!isTocSlice || atChapterEnd) -> {
                        toMarkRead.add(chapterId)
                        toMarkUnread.remove(chapterId)
                        resolved += p.chapterUrl
                    }
                    !p.completed && p.pageIndex == 0 && !isTocSlice -> {
                        pageUpdates.add(p)
                        toMarkUnread.add(chapterId)
                        toMarkRead.remove(chapterId)
                    }
                    else -> pageUpdates.add(p)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (resolved.isEmpty() && pageUpdates.isEmpty() && toMarkRead.isEmpty() && toMarkUnread.isEmpty()) {
                    throw IOException("updateProgress prepare failed", e)
                }
                break
            }
        }

        for (p in pageUpdates) {
            val chapterId = parseChapterId(p.chapterUrl) ?: continue
            try {
                val meta = chapterMetaCache[chapterId]
                    ?: throw IOException("Missing chapter meta for $chapterId")
                val tocStart = parseTocStart(p.chapterUrl)
                val absolutePage = tocStart + p.pageIndex
                val body =
                    json.encodeToJsonElement(
                        ProgressDto(
                            volumeId = meta.first,
                            chapterId = chapterId,
                            pageNum = absolutePage,
                            seriesId = seriesId,
                            libraryId = libraryId,
                        ),
                    ).toString().toRequestBody(Kavita.JSON_MEDIA_TYPE)
                client.newCall(POST("$apiUrl/Reader/progress", headersBuilder().build(), body)).await().use { progressResp ->
                    if (!progressResp.isSuccessful) {
                        if (progressResp.code == 404) {
                            resolved += p.chapterUrl
                            return@use
                        }
                        throw IOException("Progress HTTP ${progressResp.code}")
                    }
                    resolved += p.chapterUrl
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (resolved.isEmpty()) throw e
                break
            }
        }

        if (toMarkRead.isNotEmpty()) {
            try {
                val markBody =
                    buildJsonObject {
                        put("seriesId", seriesId)
                        putJsonArray("chapterIds") {
                            toMarkRead.forEach { add(JsonPrimitive(it)) }
                        }
                    }.toString().toRequestBody(Kavita.JSON_MEDIA_TYPE)
                client.newCall(POST("$apiUrl/Reader/mark-multiple-read", headersBuilder().build(), markBody)).await().use { markResp ->
                    if (!markResp.isSuccessful) {
                        throw IOException("mark-multiple-read HTTP ${markResp.code}")
                    }
                    for (p in byUrl.values) {
                        val id = parseChapterId(p.chapterUrl) ?: continue
                        if (id in toMarkRead) resolved += p.chapterUrl
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (resolved.isEmpty()) throw e
            }
        }

        if (toMarkUnread.isNotEmpty()) {
            try {
                val markBody =
                    buildJsonObject {
                        put("seriesId", seriesId)
                        putJsonArray("chapterIds") {
                            toMarkUnread.forEach { add(JsonPrimitive(it)) }
                        }
                    }.toString().toRequestBody(Kavita.JSON_MEDIA_TYPE)
                client.newCall(POST("$apiUrl/Reader/mark-multiple-unread", headersBuilder().build(), markBody)).await().use { markResp ->
                    if (!markResp.isSuccessful) {
                        throw IOException("mark-multiple-unread HTTP ${markResp.code}")
                    }
                    // Mark unread URLs resolved
                    chapterLoop@ for (p in byUrl.values) {
                        val id = parseChapterId(p.chapterUrl) ?: continue
                        if (id in toMarkUnread) resolved += p.chapterUrl
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (resolved.isEmpty()) throw e
            }
        }

        if (resolved.isEmpty() && byUrl.isNotEmpty()) {
            throw IOException("updateProgress resolved nothing")
        }
        return resolved.toList()
    }

    override suspend fun getProgress(
        manga: SManga,
        chapterUrls: List<String>,
    ): List<ChapterProgress> {
        if (chapterUrls.isEmpty()) return emptyList()

        val plainIds = linkedMapOf<Int, String>()
        val specialUrls = mutableListOf<String>()
        for (url in chapterUrls) {
            val id = parseChapterId(url)
            if (id == null) {
                throw IOException("Unparseable chapter URL")
            }
            if ("tocStart=" in url) {
                specialUrls.add(url)
            } else {
                // Whole chapters and ?split=N share the same chapter id for Series/volumes.
                plainIds[id] = url
            }
        }

        val result = mutableListOf<ChapterProgress>()

        if (plainIds.isNotEmpty()) {
            val seriesId = helper.getIdFromUrl(manga.url)
            client.newCall(GET("$apiUrl/Series/volumes?seriesId=$seriesId", headersBuilder().build())).await().use { volumesResp ->
                if (!volumesResp.isSuccessful) {
                    throw IOException("Series volumes HTTP ${volumesResp.code}")
                }
                val volumes = volumesResp.parseAs<List<VolumeDto>>()
                for (volume in volumes) {
                    for (chapter in volume.chapters) {
                        val url = plainIds[chapter.id] ?: continue
                        val lastIdx = (chapter.pages - 1).coerceAtLeast(0)
                        val pageIndex = chapter.pagesRead.coerceIn(0, lastIdx)
                        val completed = chapter.pages > 0 && chapter.pagesRead >= chapter.pages
                        result.add(
                            ChapterProgress(
                                chapterUrl = url,
                                pageIndex = pageIndex,
                                completed = completed,
                                lastModified = parseReadingProgressUtc(chapter.lastReadingProgressUtc),
                            ),
                        )
                        plainIds.remove(chapter.id)
                    }
                }
            }
            // IDs still in plainIds were not in Series/volumes → no server record; omit.
        }

        for (url in specialUrls) {
            val chapterId = parseChapterId(url)
                ?: throw IOException("Unparseable chapter URL")
            client.newCall(GET("$apiUrl/Reader/get-progress?chapterId=$chapterId", headersBuilder().build())).await().use { response ->
                if (!response.isSuccessful) {
                    if (response.code == 404) return@use
                    throw IOException("get-progress HTTP ${response.code}")
                }
                val dto = response.parseAs<ProgressDto>()
                client.newCall(GET("$apiUrl/Chapter?chapterId=$chapterId", headersBuilder().build())).await().use { chapterResp ->
                    if (!chapterResp.isSuccessful) {
                        if (chapterResp.code == 404) return@use
                        throw IOException("Chapter HTTP ${chapterResp.code}")
                    }
                    val chapterDetails = chapterResp.parseAs<ChapterDto>()
                    chapterMetaCache[chapterId] = chapterDetails.volumeId to chapterDetails.pages
                    val tocStart = parseTocStart(url)
                    val tocEnd = parseTocEnd(url)
                    val relativePage = (dto.pageNum - tocStart).coerceAtLeast(0)
                    val pageCount =
                        if (tocEnd != null) {
                            (tocEnd - tocStart).coerceAtLeast(1)
                        } else {
                            chapterDetails.pages
                        }
                    // Whole chapters: pagesRead/pages use a half-open end at page count.
                    // TOC slices use tocEnd as exclusive end (same as page list length),
                    // so the last readable index is tocEnd - 1.
                    val completed =
                        if (tocEnd != null) {
                            dto.pageNum >= tocEnd - 1 || dto.pageNum >= chapterDetails.pages
                        } else {
                            chapterDetails.pages > 0 && dto.pageNum >= chapterDetails.pages
                        }
                    result.add(
                        ChapterProgress(
                                chapterUrl = url,
                                pageIndex = relativePage.coerceAtMost((pageCount - 1).coerceAtLeast(0)),
                                completed = completed,
                                lastModified = parseReadingProgressUtc(
                                    dto.lastReadingProgressUtc ?: dto.lastModifiedUtc,
                                ),
                            ),
                    )
                }
            }
        }
        return result
    }
}
