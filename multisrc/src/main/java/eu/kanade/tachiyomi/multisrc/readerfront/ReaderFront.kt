package eu.kanade.tachiyomi.multisrc.readerfront

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

abstract class ReaderFront(
    final override val name: String,
    final override val baseUrl: String,
    final override val lang: String
) : HttpSource() {
    override val supportsLatest = true

    private val json by injectLazy<Json>()

    private val i18n = ReaderFrontI18N(lang)

    open val apiUrl = baseUrl.replaceFirst("://", "://api.")

    abstract fun getImageCDN(path: String, width: Int = 350): String

    override fun latestUpdatesRequest(page: Int) =
        GET("$apiUrl?query=${works(i18n.id, "updatedAt", "DESC", page, 12)}", headers)

    override fun popularMangaRequest(page: Int) =
        GET("$apiUrl?query=${works(i18n.id, "stub", "ASC", page, 120)}", headers)

    override fun mangaDetailsRequest(manga: SManga) =
        GET("$baseUrl/work/$lang/${manga.url}", headers)

    override fun chapterListRequest(manga: SManga) =
        GET("$apiUrl?query=${chaptersByWork(i18n.id, manga.url)}", headers)

    override fun pageListRequest(chapter: SChapter) =
        GET("$apiUrl?query=${chapterById(chapter.url.toInt())}", headers)

    override fun latestUpdatesParse(response: Response) =
        response.parse<List<Work>>("works").map {
            SManga.create().apply {
                url = it.stub
                title = it.toString()
                thumbnail_url = getImageCDN(it.thumbnail_path)
            }
        }.let { MangasPage(it, false) }

    override fun popularMangaParse(response: Response) =
        latestUpdatesParse(response)

    override fun mangaDetailsParse(response: Response) =
        response.parse<Work>("work").let {
            SManga.create().apply {
                url = it.stub
                title = it.toString()
                thumbnail_url = getImageCDN(it.thumbnail_path)
                description = it.description
                author = it.authors!!.joinToString()
                artist = it.artists!!.joinToString()
                genre = buildString {
                    if (it.adult!!) append("18+, ")
                    append(it.demographic_name!!)
                    if (it.genres!!.isNotEmpty()) {
                        append(", ")
                        it.genres.joinTo(this, transform = i18n::get)
                    }
                    append(", ")
                    append(it.type!!)
                }
                status = when {
                    it.licensed!! -> SManga.LICENSED
                    it.status_name == "on_going" -> SManga.ONGOING
                    it.status_name == "completed" -> SManga.COMPLETED
                    else -> SManga.UNKNOWN
                }
                initialized = true
            }
        }

    override fun chapterListParse(response: Response) =
        response.parse<List<Release>>("chaptersByWork").map {
            SChapter.create().apply {
                url = it.id.toString()
                name = it.toString()
                chapter_number = it.number
                date_upload = it.timestamp
            }
        }

    override fun pageListParse(response: Response) =
        response.parse<Chapter>("chapterById").let {
            it.mapIndexed { idx, page ->
                Page(idx, "", getImageCDN(it.path(page), page.width))
            }
        }

    override fun fetchMangaDetails(manga: SManga) =
        GET("$apiUrl?query=${work(i18n.id, manga.url)}", headers).let {
            client.newCall(it).asObservableSuccess().map(::mangaDetailsParse)
        }!!

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList) =
        client.newCall(popularMangaRequest(page)).asObservableSuccess().map { res ->
            popularMangaParse(res).let { mp ->
                mp.copy(mp.mangas.filter { it.title.contains(query, true) })
            }
        }!!

    private inline fun <reified T> Response.parse(name: String) =
        json.parseToJsonElement(body!!.string()).jsonObject.run {
            if (containsKey("errors")) {
                throw Error(get("errors")!![0]["message"].content)
            }
            json.decodeFromJsonElement<T>(get("data")!![name])
        }

    private operator fun JsonElement.get(key: String) = jsonObject[key]!!

    private operator fun JsonElement.get(index: Int) = jsonArray[index]

    private inline val JsonElement.content get() = jsonPrimitive.content

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList) =
        throw UnsupportedOperationException("Not used!")

    override fun searchMangaParse(response: Response) =
        throw UnsupportedOperationException("Not used!")

    override fun imageUrlParse(response: Response) =
        throw UnsupportedOperationException("Not used!")
}
