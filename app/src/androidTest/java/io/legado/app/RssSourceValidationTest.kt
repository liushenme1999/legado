package io.legado.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import io.legado.app.data.entities.RssSource
import io.legado.app.model.rss.Rss
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RssSourceValidationTest {

    private val tag = "RssSourceValidation"

    @Test
    fun validateAllSources() = runBlocking {
        val sources = loadSources()
        val failures = mutableListOf<String>()
        sources.forEach { source ->
            try {
                val (articles, _) = Rss.getArticlesAwait(
                    source.sourceName,
                    source.sourceUrl,
                    source,
                    0
                )
                Log.i(tag, "LIST ${source.sourceName}: ${articles.size} articles")
                if (articles.isEmpty()) {
                    failures += "${source.sourceName}: empty article list"
                }
            } catch (throwable: Throwable) {
                Log.e(tag, "LIST ${source.sourceName}: failed", throwable)
                failures += "${source.sourceName}: ${throwable.message}"
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun validateArticleContent() = runBlocking {
        val names = setOf("IT之家", "少数派", "机核GCORES", "极客公园", "36氪", "Nature")
        val failures = mutableListOf<String>()
        loadSources().filter { it.sourceName in names }.forEach { source ->
            try {
                val (articles, _) = Rss.getArticlesAwait(
                    source.sourceName,
                    source.sourceUrl,
                    source,
                    0
                )
                assertTrue("${source.sourceName}: empty list", articles.isNotEmpty())
                articles.take(3).forEach { article ->
                    assertTrue(
                        "${source.sourceName}: description is not null ${article.link}",
                        article.description.isNullOrBlank()
                    )
                    val content = Rss.getContentAwait(
                        article,
                        requireNotNull(source.ruleContent),
                        source
                    )
                    val textLength = Jsoup.parse(content).text().length
                    Log.i(
                        tag,
                        "CONTENT ${source.sourceName}: text=$textLength link=${article.link}"
                    )
                    assertTrue(
                        "${source.sourceName}: short content $textLength ${article.link}",
                        textLength >= 50
                    )
                    assertFalse(
                        "${source.sourceName}: XML returned ${article.link}",
                        content.trimStart().startsWith("<?xml")
                    )
                }
            } catch (throwable: Throwable) {
                Log.e(tag, "CONTENT ${source.sourceName}: failed", throwable)
                failures += "${source.sourceName}: ${throwable.stackTraceToString()}"
            }
        }
        assertTrue(failures.joinToString("\n\n"), failures.isEmpty())
    }

    private fun loadSources(): Array<RssSource> {
        val context = InstrumentationRegistry.getInstrumentation().context
        val json = context.assets.open("rssSource_verified.json")
            .bufferedReader()
            .use { it.readText() }
        return Gson().fromJson(json, Array<RssSource>::class.java)
    }
}
