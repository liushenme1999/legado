package io.legado.app.model.localBook

import android.net.Uri
import androidx.core.net.toUri
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.getLocalUri
import io.legado.app.help.book.isLocalModified
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.EncodingDetect
import io.legado.app.utils.FileDoc
import io.legado.app.utils.HtmlFormatter
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.StringUtils
import io.legado.app.utils.find
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.isUri
import io.legado.app.utils.openInputStream
import io.legado.app.utils.printOnDebug
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.charset.Charset

/**
 * Markdown (.md / .markdown) 文件解析.
 *
 * 章节切分:
 *   - 扫描所有"顶层" ATX 标题(忽略代码围栏内的 `#`),取出现最浅的一级作为章节分隔
 *   - 第一个标题之前的内容生成 "前言" 章节
 *   - 整篇没有标题时,整文件视为一个章节
 *
 * 章节渲染:
 *   - 用 commonmark 将 Markdown 解析成 AST 并渲染为 HTML
 *   - 通过 [HtmlFormatter.formatKeepImg] 转成阅读引擎可消费的"纯文本 + <img>"形式
 *
 * 图片:
 *   - 网络图片 (http/https) 由 BookHelp 走原有下载链路
 *   - 相对路径会在 [getImage] 中解析为同目录文件
 */
class MdFile(var book: Book) {

    companion object : BaseLocalBookParse {
        private var mFile: MdFile? = null

        @Synchronized
        private fun getMFile(book: Book): MdFile {
            if (mFile == null
                || mFile?.book?.bookUrl != book.bookUrl
                || book.isLocalModified()
            ) {
                mFile = MdFile(book)
                return mFile!!
            }
            mFile?.book = book
            return mFile!!
        }

        @Synchronized
        override fun getChapterList(book: Book): ArrayList<BookChapter> {
            return getMFile(book).getChapterList()
        }

        @Synchronized
        override fun getContent(book: Book, chapter: BookChapter): String? {
            return getMFile(book).getContent(chapter)
        }

        @Synchronized
        override fun getImage(book: Book, href: String): InputStream? {
            return getMFile(book).getImage(href)
        }

        @Synchronized
        override fun upBookInfo(book: Book) {
            getMFile(book).upBookInfo()
        }

        /**
         * 为 Markwon 视图提供的入口: 取章节原始 markdown(已剥掉首行章节标题),
         * 同时把所有相对/本地的图片链接重写为可被 Glide 直接加载的绝对 URI 串.
         */
        @Synchronized
        fun getMarkwonMarkdown(book: Book, chapter: BookChapter): String? {
            return getMFile(book).getMarkwonMarkdown(chapter)
        }

        fun clear() {
            mFile = null
        }
    }

    private val parser: Parser = Parser.builder().build()
    private val htmlRenderer: HtmlRenderer = HtmlRenderer.builder().build()

    private data class ParsedChapter(
        val title: String,
        val markdown: String,
        val hasHeadingLine: Boolean,
    )

    private var charset: Charset = Charsets.UTF_8
    private var chaptersCache: List<ParsedChapter>? = null

    @Throws(FileNotFoundException::class)
    private fun ensureParsed(): List<ParsedChapter> {
        chaptersCache?.let { return it }
        val bytes = LocalBook.getBookInputStream(book).use { it.readBytes() }
        if (book.charset.isNullOrBlank()) {
            book.charset = EncodingDetect.getEncode(bytes)
        }
        charset = book.fileCharset()
        val text = String(bytes, charset).let { s ->
            if (s.isNotEmpty() && s[0] == '\uFEFF') s.substring(1) else s
        }
        val result = splitChapters(text)
        chaptersCache = result
        return result
    }

    @Throws(FileNotFoundException::class)
    fun getChapterList(): ArrayList<BookChapter> {
        val parsed = ensureParsed()
        var totalChars = 0
        val list = ArrayList<BookChapter>(parsed.size)
        parsed.forEachIndexed { index, item ->
            val chapter = BookChapter()
            chapter.bookUrl = book.bookUrl
            chapter.index = index
            chapter.title = item.title.ifBlank { "无标题章节" }
            chapter.url = MD5Utils.md5Encode16(book.originName + index + chapter.title)
            chapter.wordCount = StringUtils.wordCountFormat(item.markdown.length)
            totalChars += item.markdown.length
            list.add(chapter)
        }
        book.wordCount = StringUtils.wordCountFormat(totalChars)
        return list
    }

    fun getContent(chapter: BookChapter): String? {
        val parsed = ensureParsed()
        val item = parsed.getOrNull(chapter.index) ?: return null
        return renderMarkdown(item.markdown, stripLeadingHeading = item.hasHeadingLine)
    }

    /**
     * 取章节原始 markdown 给 Markwon 用. 同时把 `![alt](xxx)` 和 `<img src="xxx">` 里的
     * 相对/本地路径预先解析成 Glide 能加载的绝对 URI(`file://...` 或 `content://...`).
     */
    fun getMarkwonMarkdown(chapter: BookChapter): String? {
        val parsed = ensureParsed()
        val item = parsed.getOrNull(chapter.index) ?: return null
        val source = if (item.hasHeadingLine) {
            stripHeadingLines(item.markdown)
        } else {
            item.markdown
        }
        if (source.isBlank()) return ""
        return rewriteImageRefs(source)
    }

    private fun stripHeadingLines(markdown: String): String {
        val firstNewline = markdown.indexOf('\n')
        if (firstNewline < 0) return ""
        val rest = markdown.substring(firstNewline + 1)
        // Setext 标题占两行: 内容行 + ===/--- 装饰行
        val nextLineEnd = rest.indexOf('\n')
        val maybeUnderline = if (nextLineEnd == -1) rest else rest.substring(0, nextLineEnd)
        val isSetextUnderline = maybeUnderline.isNotBlank()
            && (maybeUnderline.trim().all { it == '=' }
                || maybeUnderline.trim().all { it == '-' })
        return if (isSetextUnderline && nextLineEnd >= 0) {
            rest.substring(nextLineEnd + 1)
        } else {
            rest
        }
    }

    private val mdImageRegex =
        Regex("(!\\[[^\\]]*]\\()([^)\\s]+)(\\s+\"[^\"]*\")?(\\))")
    private val htmlImageSrcRegex =
        Regex("(<img[^>]*\\ssrc=[\"'])([^\"']+)([\"'])", RegexOption.IGNORE_CASE)

    private fun rewriteImageRefs(markdown: String): String {
        val afterMd = mdImageRegex.replace(markdown) { match ->
            val prefix = match.groupValues[1]
            val src = match.groupValues[2]
            val title = match.groupValues[3]
            val suffix = match.groupValues[4]
            val resolved = resolveImageUriString(src) ?: src
            "$prefix$resolved$title$suffix"
        }
        return htmlImageSrcRegex.replace(afterMd) { match ->
            val prefix = match.groupValues[1]
            val src = match.groupValues[2]
            val suffix = match.groupValues[3]
            val resolved = resolveImageUriString(src) ?: src
            "$prefix$resolved$suffix"
        }
    }

    /**
     * 解析 `<img src>` / `![](src)` 里的链接为可加载的 URI 串.
     * - 网络链接(http/https/data:) 原样返回 null(交给 Markwon 自己处理)
     * - 绝对路径 / file://: 直接转 `file://` URI
     * - 相对路径: 先在 .md 同目录递归找, 找不到的话再去 defaultBookTreeUri / importBookPath 兜底
     */
    private fun resolveImageUriString(src: String): String? {
        if (src.isBlank() || src.isAbsUrl() || src.startsWith("data:")) return null
        val normalized = src.substringBefore('#').substringBefore('?')
        val bookUri = runCatching { book.getLocalUri() }.getOrNull() ?: return null

        if (normalized.startsWith("file://")) {
            return normalized
        }
        if (normalized.startsWith('/')) {
            val file = File(normalized)
            return if (file.exists()) Uri.fromFile(file).toString() else null
        }

        if (!bookUri.isContentScheme()) {
            val parentFile = bookUri.path?.let { File(it).parentFile } ?: return null
            val rel = normalized.trimStart('.', '/')
            File(parentFile, rel).takeIf { it.exists() && it.isFile }?.let {
                return Uri.fromFile(it).toString()
            }
            val fileName = rel.substringAfterLast('/')
            parentFile.walkTopDown().maxDepth(5)
                .firstOrNull { !it.isDirectory && it.name == fileName }
                ?.let { return Uri.fromFile(it).toString() }
            return null
        }

        // content:// (SAF) 兜底: 去配置的书籍目录里按文件名查找
        val fileName = normalized.trimStart('.', '/').substringAfterLast('/')
        val searchRoots = sequenceOf(
            AppConfig.defaultBookTreeUri,
            AppConfig.importBookPath
        ).filterNotNull().distinct()
        for (root in searchRoots) {
            val rootUri = if (root.isUri()) root.toUri() else Uri.fromFile(File(root))
            val rootDoc = runCatching { FileDoc.fromUri(rootUri, true) }.getOrNull() ?: continue
            rootDoc.find(fileName, depth = 4, maxFinds = 200)?.let {
                return it.uri.toString()
            }
        }
        return null
    }

    fun upBookInfo() {
        try {
            val parsed = ensureParsed()
            if (book.name.isBlank()) {
                book.name = book.originName.substringBeforeLast(".")
            }
            if (book.intro.isNullOrBlank()) {
                val intro = parsed.firstOrNull()?.markdown
                    ?.lineSequence()
                    ?.map { it.trim() }
                    ?.firstOrNull { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("```") }
                if (!intro.isNullOrBlank()) {
                    book.intro = if (intro.length > 500) intro.substring(0, 500) else intro
                }
            }
        } catch (e: Exception) {
            AppLog.put("更新MD书籍信息失败\n${e.localizedMessage}", e)
            e.printOnDebug()
        }
    }

    fun getImage(href: String): InputStream? {
        if (href.isBlank() || href.isAbsUrl()) return null
        return runCatching {
            // 直接的本地绝对路径或 file://
            val direct = when {
                href.startsWith("file://") -> File(href.removePrefix("file://"))
                href.startsWith('/') -> File(href)
                else -> null
            }
            if (direct?.exists() == true) {
                return@runCatching direct.inputStream()
            }
            resolveSiblingInputStream(href)
        }.onFailure {
            AppLog.put("加载MD图片失败 $href\n${it.localizedMessage}", it)
        }.getOrNull()
    }

    /**
     * 把 Markdown 切成多个章节.
     *
     * 章节级别选取规则:
     *   - 优先选取"出现次数 >= 2 且最浅"的标题级别,这样
     *     `# 书名 / ## 第一章 / ## 第二章` 这种结构会按 `##` 切分.
     *   - 若没有任何级别出现 >= 2 次,则退化为整篇最浅级别.
     */
    private fun splitChapters(text: String): List<ParsedChapter> {
        val markers = scanHeadingMarkers(text)
        if (markers.isEmpty()) {
            val title = book.name.ifBlank { book.originName.substringBeforeLast(".") }
            return listOf(ParsedChapter(title, text, hasHeadingLine = false))
        }
        val countsByLevel = IntArray(7)
        markers.forEach { countsByLevel[it.level]++ }
        val splitLevel = (1..6).firstOrNull { countsByLevel[it] >= 2 }
            ?: markers.minOf { it.level }
        val splitMarkers = markers.filter { it.level == splitLevel }
        val chapters = ArrayList<ParsedChapter>(splitMarkers.size + 1)
        val firstStart = splitMarkers.first().lineStart
        if (firstStart > 0) {
            val preface = text.substring(0, firstStart).trim()
            if (preface.isNotEmpty()) {
                // 序章里如果有更浅级别的标题(例如 H1 书名),把第一个非空非标题行当作展示标题
                val prefaceTitle = preface.lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.isNotBlank() }
                    ?.removePrefix("#")?.trimStart('#', ' ', '\t')
                    ?.ifBlank { "前言" } ?: "前言"
                chapters.add(ParsedChapter(prefaceTitle, preface, hasHeadingLine = false))
            }
        }
        for (i in splitMarkers.indices) {
            val cur = splitMarkers[i]
            val end = if (i + 1 < splitMarkers.size) splitMarkers[i + 1].lineStart else text.length
            val raw = text.substring(cur.lineStart, end).trim()
            chapters.add(ParsedChapter(cur.title, raw, hasHeadingLine = true))
        }
        return chapters
    }

    private data class HeadingMarker(
        val level: Int,
        val title: String,
        /** 章节内容在原文中的起始位置(对于 ATX = `#` 所在行;对于 Setext = 内容行) */
        val lineStart: Int,
    )

    /**
     * 扫描所有标题行:
     *   - ATX 风格: `# Title` 至 `###### Title`,跳过代码围栏(``` / ~~~)内部
     *   - Setext 风格: 内容行的下一行是 `===+`(H1) 或 `---+`(H2)
     */
    private fun scanHeadingMarkers(text: String): List<HeadingMarker> {
        val markers = mutableListOf<HeadingMarker>()
        var inFence = false
        var fenceChar = '`'
        var fenceLen = 0

        // 先按 \n 切片为(lineStart, content)
        data class Line(val start: Int, val raw: String)
        val lines = mutableListOf<Line>()
        run {
            var idx = 0
            while (idx <= text.length) {
                val nl = text.indexOf('\n', idx).let { if (it == -1) text.length else it }
                lines.add(Line(idx, text.substring(idx, nl)))
                if (nl == text.length) break
                idx = nl + 1
            }
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.raw.trimStart()
            val indent = line.raw.length - trimmed.length

            if (indent < 4) {
                val fenceInfo = detectFence(trimmed)
                if (fenceInfo != null) {
                    if (!inFence) {
                        inFence = true
                        fenceChar = fenceInfo.first
                        fenceLen = fenceInfo.second
                    } else if (fenceInfo.first == fenceChar && fenceInfo.second >= fenceLen) {
                        inFence = false
                    }
                    i++
                    continue
                }

                if (!inFence) {
                    // ATX
                    if (trimmed.startsWith('#')) {
                        var level = 0
                        while (level < trimmed.length && trimmed[level] == '#' && level < 6) level++
                        val afterHashes = if (level < trimmed.length) trimmed[level] else ' '
                        if (level in 1..6 && (afterHashes == ' ' || afterHashes == '\t')) {
                            val title = trimmed.substring(level).trim()
                                .trimEnd('#').trim()
                            markers.add(HeadingMarker(level, title, line.start))
                            i++
                            continue
                        }
                    }
                    // Setext: 内容行 + 下一行全是 = 或 -
                    if (trimmed.isNotEmpty() && i + 1 < lines.size) {
                        val nextTrim = lines[i + 1].raw.trim()
                        if (nextTrim.isNotEmpty() && nextTrim.all { it == '=' }) {
                            markers.add(HeadingMarker(1, trimmed.trim(), line.start))
                            i += 2
                            continue
                        }
                        if (nextTrim.length >= 2 && nextTrim.all { it == '-' }) {
                            markers.add(HeadingMarker(2, trimmed.trim(), line.start))
                            i += 2
                            continue
                        }
                    }
                }
            }
            i++
        }
        return markers
    }

    /** 返回 (fenceChar, length) 或 null. 仅识别 ``` 或 ~~~ 形式的代码围栏. */
    private fun detectFence(trimmed: String): Pair<Char, Int>? {
        if (trimmed.isEmpty()) return null
        val ch = trimmed[0]
        if (ch != '`' && ch != '~') return null
        var n = 0
        while (n < trimmed.length && trimmed[n] == ch) n++
        if (n < 3) return null
        return ch to n
    }

    /**
     * 渲染 Markdown 片段为阅读引擎可用的 "段落 + <img>" 字符串.
     *
     * Legado 的阅读引擎只渲染纯文本 + `<img>` 标签, 不支持富文本(粗体/斜体/超链接).
     * 我们能做的是:
     *   - 把 Markdown 解析成 HTML
     *   - 把 HTML 里"无法用空白表达边界"的标签(如 li / blockquote / hr) 转成段落分隔,
     *     避免列表/引用挤成一行
     *   - 然后交给 HtmlFormatter 统一清洗成 Legado 阅读引擎可消费的样子
     */
    private fun renderMarkdown(markdown: String, stripLeadingHeading: Boolean): String {
        val source = if (stripLeadingHeading) {
            // ATX 占 1 行, Setext 占 2 行; 但 splitChapters 阶段我们用 startIndex 截断时已经
            // trim 过空白,所以这里按行数剥即可(默认 1 行,Setext 调用方会传 2 但我们不再区分,
            // 多剥一行是 ===/--- 装饰行,内容仍然是空白行,无副作用)
            val firstNewline = markdown.indexOf('\n')
            if (firstNewline >= 0) {
                val rest = markdown.substring(firstNewline + 1)
                val nextLineEnd = rest.indexOf('\n')
                val maybeUnderline =
                    if (nextLineEnd == -1) rest else rest.substring(0, nextLineEnd)
                val isSetextUnderline = maybeUnderline.isNotBlank()
                    && (maybeUnderline.trim().all { it == '=' }
                        || maybeUnderline.trim().all { it == '-' })
                if (isSetextUnderline && nextLineEnd >= 0) {
                    rest.substring(nextLineEnd + 1)
                } else {
                    rest
                }
            } else ""
        } else {
            markdown
        }
        if (source.isBlank()) return ""
        val document = parser.parse(source)
        val rawHtml = htmlRenderer.render(document)
        val withBreaks = injectBlockBreaks(rawHtml)
        return HtmlFormatter.formatKeepImg(withBreaks)
    }

    /**
     * 在 HtmlFormatter 看不出边界的块级 HTML 标签外侧补 `<p>...</p>`,
     * 让最终文本里列表、引用、水平线等都能换行展示.
     */
    private fun injectBlockBreaks(html: String): String {
        return html
            .replace(Regex("<li[^>]*>"), "<p>• ")
            .replace("</li>", "</p>")
            .replace(Regex("<blockquote[^>]*>"), "<p>")
            .replace("</blockquote>", "</p>")
            .replace(Regex("<pre[^>]*>"), "<p>")
            .replace("</pre>", "</p>")
            .replace(Regex("<hr\\s*/?>"), "<p>────────</p>")
            // 表格简单拆分
            .replace(Regex("<tr[^>]*>"), "<p>")
            .replace("</tr>", "</p>")
            .replace(Regex("</t[hd]>\\s*<t[hd][^>]*>"), " | ")
            .replace(Regex("<t[hd][^>]*>"), "")
            .replace(Regex("</t[hd]>"), "")
    }

    /**
     * 查找相对路径指向的资源文件.
     * - file:// 书籍: 直接走文件系统
     * - content:// (SAF) 书籍: 兜底到 defaultBookTreeUri / importBookPath 中按文件名查找
     */
    private fun resolveSiblingInputStream(href: String): InputStream? {
        val normalized = href.substringBefore('#').substringBefore('?').trimStart('.', '/')
        if (normalized.isBlank()) return null
        val fileName = normalized.substringAfterLast('/')

        val bookUri = runCatching { book.getLocalUri() }.getOrNull() ?: return null
        if (!bookUri.isContentScheme()) {
            val parentFile = bookUri.path?.let { File(it).parentFile } ?: return null
            File(parentFile, normalized)
                .takeIf { it.exists() && it.isFile }
                ?.let { return it.inputStream() }
            return parentFile.walkTopDown()
                .maxDepth(5)
                .firstOrNull { !it.isDirectory && it.name == fileName }
                ?.inputStream()
        }

        val searchRoots = sequenceOf(
            AppConfig.defaultBookTreeUri,
            AppConfig.importBookPath
        ).filterNotNull().distinct()
        for (root in searchRoots) {
            val rootUri = if (root.isUri()) root.toUri() else Uri.fromFile(File(root))
            val rootDoc = runCatching { FileDoc.fromUri(rootUri, true) }.getOrNull() ?: continue
            rootDoc.find(fileName, depth = 4, maxFinds = 200)?.let {
                return it.openInputStream().getOrNull()
            }
        }
        return null
    }

}
