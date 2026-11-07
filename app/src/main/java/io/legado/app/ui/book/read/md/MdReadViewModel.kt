package io.legado.app.ui.book.read.md

import android.app.Application
import android.content.Intent
import androidx.lifecycle.MutableLiveData
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.isMd
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.localBook.MdFile
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MdReadViewModel(application: Application) : BaseViewModel(application) {

    val bookLiveData = MutableLiveData<Book>()
    val chaptersLiveData = MutableLiveData<List<BookChapter>>()

    /** 当前展示的章节内容 */
    data class ChapterPayload(
        val chapter: BookChapter,
        /** 原始 markdown(已剥掉首行章节标题, 图片相对路径已转绝对 URI) */
        val markdown: String,
        val totalCount: Int,
        /** 期望恢复的滚动 Y 坐标(像素) */
        val initialScrollPos: Int,
    )

    val chapterContentLiveData = MutableLiveData<ChapterPayload>()
    val errorLiveData = MutableLiveData<String>()

    var book: Book? = null
        private set
    var chapters: List<BookChapter> = emptyList()
        private set

    fun init(intent: Intent) {
        execute {
            val bookUrl = intent.getStringExtra("bookUrl") ?: return@execute
            val bk = appDb.bookDao.getBook(bookUrl) ?: return@execute
            if (!bk.isMd) {
                context.toastOnUi("非 Markdown 书籍")
                return@execute
            }
            book = bk
            bookLiveData.postValue(bk)
            // 打开书时刷新 durChapterTime, 让最近浏览的书在书架顶端
            bk.durChapterTime = System.currentTimeMillis()
            withContext(Dispatchers.IO) { bk.save() }
            loadChaptersInternal(bk)
        }.onError {
            errorLiveData.postValue(it.localizedMessage ?: "ERROR")
        }
    }

    private suspend fun loadChaptersInternal(book: Book) {
        var list = appDb.bookChapterDao.getChapterList(book.bookUrl)
        if (list.isEmpty()) {
            list = withContext(Dispatchers.IO) {
                LocalBook.getChapterList(book).also { fresh ->
                    appDb.bookChapterDao.delByBook(book.bookUrl)
                    appDb.bookChapterDao.insert(*fresh.toTypedArray())
                    book.save()
                }
            }
        }
        chapters = list
        chaptersLiveData.postValue(list)
        val durIndex = book.durChapterIndex.coerceIn(0, (list.size - 1).coerceAtLeast(0))
        if (durIndex != book.durChapterIndex) {
            book.durChapterIndex = durIndex
        }
        loadChapter(durIndex)
    }

    /** 强制重新生成目录(用户主动下拉刷新等场景) */
    fun refreshChapters() {
        val bk = book ?: return
        execute {
            val fresh = withContext(Dispatchers.IO) {
                MdFile.clear()
                LocalBook.getChapterList(bk).also {
                    appDb.bookChapterDao.delByBook(bk.bookUrl)
                    appDb.bookChapterDao.insert(*it.toTypedArray())
                    bk.save()
                }
            }
            chapters = fresh
            chaptersLiveData.postValue(fresh)
            val durIndex = bk.durChapterIndex.coerceIn(0, (fresh.size - 1).coerceAtLeast(0))
            if (durIndex != bk.durChapterIndex) {
                bk.durChapterIndex = durIndex
            }
            loadChapter(durIndex)
        }.onError {
            errorLiveData.postValue(it.localizedMessage ?: "ERROR")
        }
    }

    /**
     * 切换到指定章节并渲染.
     * @param requestedScrollPos 期望恢复的滚动 Y 坐标(像素), -1 表示按默认规则(同章保持原位,
     * 跨章重置到 0).
     */
    fun loadChapter(index: Int, requestedScrollPos: Int = -1) {
        val bk = book ?: return
        if (chapters.isEmpty()) return
        val safeIndex = index.coerceIn(0, chapters.size - 1)
        val chapter = chapters[safeIndex]
        execute {
            val markdown = withContext(Dispatchers.IO) {
                MdFile.getMarkwonMarkdown(bk, chapter) ?: ""
            }
            val sameChapter = bk.durChapterIndex == safeIndex
            val targetScrollPos = when {
                requestedScrollPos >= 0 -> requestedScrollPos
                sameChapter -> bk.durChapterPos
                else -> 0
            }
            bk.durChapterIndex = safeIndex
            bk.durChapterTitle = chapter.title
            bk.durChapterPos = targetScrollPos
            bk.durChapterTime = System.currentTimeMillis()
            withContext(Dispatchers.IO) { bk.save() }
            chapterContentLiveData.postValue(
                ChapterPayload(chapter, markdown, chapters.size, targetScrollPos)
            )
        }.onError {
            errorLiveData.postValue(it.localizedMessage ?: "ERROR")
        }
    }

    fun saveScrollPos(pos: Int) {
        val bk = book ?: return
        if (bk.durChapterPos == pos) return
        bk.durChapterPos = pos
        bk.durChapterTime = System.currentTimeMillis()
        execute { bk.save() }
    }
}
