package io.legado.app.ui.book.read.md

import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.view.textclassifier.TextClassifier
import android.widget.SeekBar
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.Bookmark
import io.legado.app.databinding.ActivityMdReadBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.ui.book.bookmark.BookmarkDialog
import io.legado.app.ui.book.read.config.BgTextConfigDialog
import io.legado.app.ui.book.toc.TocActivityResult
import io.legado.app.ui.widget.seekbar.SeekBarChangeListener
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.observeEvent
import io.legado.app.utils.setLightStatusBar
import io.legado.app.utils.setNavigationBarColorAuto
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.glide.GlideImagesPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * 给 `.md` 书籍准备的专属阅读视图.
 *
 * - 用 Markwon 渲染章节内容(支持段落、列表、引用、代码块、表格、图片等)
 * - 顶部 TitleBar、底部两行功能栏的样式与 [io.legado.app.ui.book.read.ReadBookActivity] 对齐
 * - 目录/书签复用应用通用的 [io.legado.app.ui.book.toc.TocActivity]
 * - 顶部菜单提供: 书签 / 刷新目录 / 帮助
 * - 退出时保存阅读进度 (durChapterIndex + scrollY, 见 onPause)
 */
class MdReadActivity : VMBaseActivity<ActivityMdReadBinding, MdReadViewModel>() {

    override val binding by viewBinding(ActivityMdReadBinding::inflate)
    override val viewModel by viewModels<MdReadViewModel>()

    private val markwon: Markwon by lazy {
        Markwon.builder(this)
            .usePlugin(GlideImagesPlugin.create(this))
            .usePlugin(HtmlPlugin.create())
            .usePlugin(TablePlugin.create(this))
            .build()
    }

    /**
     * 跳转 TocActivity 后返回时切到选中的章节.
     * - 从 "目录" tab 返回: second = 0
     * - 从 "书签" tab 返回: second = 书签创建时记录的 scrollY, 直接恢复到对应位置
     */
    private val tocActivity = registerForActivityResult(TocActivityResult()) { result ->
        result?.let {
            viewModel.loadChapter(it.first, it.second)
        }
    }

    /** 书签/章节恢复滚动时挂起的布局监听, 新一次恢复前先移除避免叠加. */
    private var scrollRestoreLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    private fun removeScrollRestoreLayoutListener() {
        scrollRestoreLayoutListener?.let { listener ->
            val obs = binding.scrollView.viewTreeObserver
            if (obs.isAlive) {
                obs.removeOnGlobalLayoutListener(listener)
            }
            scrollRestoreLayoutListener = null
        }
    }

    /** 每次新章节内容到来时递增, 用于丢弃过期的滚动恢复任务(Markwon 排版异步导致). */
    private var scrollRestoreGeneration = 0
    private var seekbarTracking = false

    // 菜单浮动显隐控制
    private var menuVisible = true
    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }
    private val tapTimeout by lazy {
        ViewConfiguration.getTapTimeout() + ViewConfiguration.getDoubleTapTimeout() / 2
    }

    /** "长滑动" 切章阈值: 屏幕宽度的 1/4, 不低于 120dp. */
    private val horizontalSwipeThreshold by lazy {
        val width = binding.rootLayout.width.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels
        val minPx = (120 * resources.displayMetrics.density).toInt()
        maxOf(width / 4, minPx)
    }

    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchDownTime = 0L
    private var touchMoved = false
    private var chapterSwipeTriggered = false

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            binding.tvContent.setTextClassifier(TextClassifier.NO_OP)
        }
        initView()
        observe()
        registerBackHandler()
        applyReadStyle()
        viewModel.init(intent)
    }

    private fun registerBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        })
    }

    private fun initView() {
        binding.titleBar.setNavigationOnClickListener { finish() }
        applySystemBarInsets()

        binding.refreshLayout.setOnRefreshListener {
            viewModel.refreshChapters()
        }

        // 上一章 / 下一章
        binding.tvPre.setOnClickListener { goToPrevChapter() }
        binding.tvNext.setOnClickListener { goToNextChapter() }

        // 章节进度条
        binding.seekChapter.setOnSeekBarChangeListener(object : SeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar) {
                seekbarTracking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                seekbarTracking = false
                if (viewModel.chapters.isEmpty()) return
                val target = seekBar.progress.coerceIn(0, viewModel.chapters.size - 1)
                if (target != viewModel.book?.durChapterIndex) {
                    viewModel.loadChapter(target)
                }
            }
        })

        // 底栏 "目录" 按钮: 跳转通用 TocActivity (目录 + 书签两个 tab)
        binding.llCatalog.setOnClickListener { openTocActivity() }
        // 底栏 "界面" 按钮: 打开既有的文字颜色 & 背景设置对话框
        binding.llInterface.setOnClickListener {
            showDialogFragment<BgTextConfigDialog>()
        }
        binding.llRefresh.setOnClickListener {
            binding.refreshLayout.isRefreshing = true
            viewModel.refreshChapters()
        }

        // 悬浮日/夜间模式按钮 (从底栏移到悬浮 FAB, 与 ReadBookActivity 一致)
        binding.fabNightTheme.setOnClickListener {
            AppConfig.isNightTheme = !AppConfig.isNightTheme
            ThemeConfig.applyDayNight(this)
        }

        // 滚动浏览时自动隐藏顶部/底部菜单
        binding.scrollView.setOnScrollChangeListener(
            NestedScrollView.OnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
                if (menuVisible && abs(scrollY - oldScrollY) > touchSlop) {
                    setMenuVisible(false)
                }
            }
        )

        upNightThemeIcon()
    }

    /**
     * 让内容/底栏正确避开状态栏和底部小白条:
     * - [NestedScrollView] 加上状态栏 / 导航栏的 padding, 配合默认的 `clipToPadding=true`,
     *   文字滚动时会被裁剪在系统栏区域之外, 不再与状态栏图标或小白条重叠.
     * - 底部功能栏加上导航栏 bottom inset, 整体上移避开小白条.
     * TitleBar 自身声明了 `fitStatusBar=true`, 已经会向状态栏留出 padding, 不需重复处理.
     */
    private fun applySystemBarInsets() {
        val scroll = binding.scrollView
        val initialScrollTop = scroll.paddingTop
        val initialScrollBottom = scroll.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { _, windowInsets ->
            val bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            scroll.setPadding(
                scroll.paddingLeft,
                initialScrollTop + bars.top,
                scroll.paddingRight,
                initialScrollBottom + bars.bottom
            )
            windowInsets
        }

        val bottomMenu = binding.bottomMenu
        val initialBottomMenuBottom = bottomMenu.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(bottomMenu) { _, windowInsets ->
            val bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            bottomMenu.setPadding(
                bottomMenu.paddingLeft,
                bottomMenu.paddingTop,
                bottomMenu.paddingRight,
                initialBottomMenuBottom + bars.bottom
            )
            windowInsets
        }

        ViewCompat.requestApplyInsets(scroll)
        ViewCompat.requestApplyInsets(bottomMenu)
    }

    private fun observe() {
        viewModel.bookLiveData.observe(this) { book ->
            binding.titleBar.title = book.name
        }
        viewModel.chaptersLiveData.observe(this) {
            binding.refreshLayout.isRefreshing = false
            upChapterNavState()
        }
        viewModel.chapterContentLiveData.observe(this) { payload ->
            binding.tvChapterTitle.text = payload.chapter.title
            binding.titleBar.subtitle =
                "${payload.chapter.index + 1} / ${payload.totalCount}"
            scrollRestoreGeneration++
            val restoreGen = scrollRestoreGeneration
            val scrollY = payload.initialScrollPos
            renderMarkdown(payload.markdown, restoreGen, scrollY)
            upChapterNavState()
        }
        viewModel.errorLiveData.observe(this) {
            binding.refreshLayout.isRefreshing = false
            toastOnUi(it)
        }
        observeEvent<String>(EventBus.RECREATE) {
            recreate()
        }
        // 文字颜色 / 背景在 BgTextConfigDialog 里改完会广播 UP_CONFIG
        observeEvent<ArrayList<Int>>(EventBus.UP_CONFIG) { codes ->
            // 1 = 背景, 2 = 样式 (含文字颜色), 3 = 背景透明度
            if (codes.any { it == 1 || it == 2 || it == 3 }) {
                applyReadStyle()
            }
        }
    }

    /**
     * 与 [io.legado.app.ui.book.read.BaseReadBookActivity] 对齐:
     * - 顶部/底部功能栏显示时: 系统导航栏(小白条区域)与底栏 [R.color.background_menu] 同色
     * - 功能栏隐藏且开启沉浸式导航栏时: 用阅读纸面 [ReadBookConfig.bgMeanColor] 贴合正文
     * - 非沉浸式时隐藏菜单仍走 [super] 的主题导航栏色
     */
    override fun upNavigationBarColor() {
        if (menuVisible) {
            setNavigationBarColorAuto(getCompatColor(R.color.background_menu))
            return
        }
        if (!AppConfig.immNavigationBar) {
            super.upNavigationBarColor()
        } else {
            setNavigationBarColorAuto(ReadBookConfig.bgMeanColor)
        }
    }

    /**
     * [BgTextConfigDialog] 中切换「深色状态栏图标」时回调, 与 TXT 阅读器里 [io.legado.app.ui.book.read.ReadBookActivity.upSystemUiVisibility] 对齐到 Markdown 页可用的子集.
     */
    fun syncSystemUiFromReadConfigDialog() {
        setLightStatusBar(ReadBookConfig.durConfig.curStatusIconDark())
        upNavigationBarColor()
    }

    /**
     * 把 [ReadBookConfig] 中的文字颜色和背景应用到 Markdown 阅读视图.
     *
     * - 纸张背景应用到整个根布局, 与 ReadBookActivity 一样可以延伸到状态栏 / 导航栏后面;
     *   TitleBar / 底栏隐藏时, 整屏都是纸张背景
     * - 文字颜色应用到正文 / 章节标题
     * - 顶部 TitleBar / 底部功能栏 / 悬浮 FAB 保留 app 主题色 (与 ReadBookActivity 一致)
     * - [ReadBookConfig.upBg] 会更新 [ReadBookConfig.bgMeanColor], 随后刷新系统导航栏颜色以贴合纸面
     */
    private fun applyReadStyle() {
        val root = binding.rootLayout
        val tv = binding.tvContent
        val title = binding.tvChapterTitle

        val textColor = ReadBookConfig.textColor
        tv.setTextColor(textColor)
        tv.setLinkTextColor(textColor)
        title.setTextColor(textColor)

        val applyBg: (Int, Int) -> Unit = { w, h ->
            ReadBookConfig.upBg(w, h)
            root.background = ReadBookConfig.bg
            upNavigationBarColor()
        }
        if (root.width > 0 && root.height > 0) {
            applyBg(root.width, root.height)
        } else {
            root.post {
                if (root.width > 0 && root.height > 0) {
                    applyBg(root.width, root.height)
                }
            }
        }
    }

    private fun upChapterNavState() {
        val total = viewModel.chapters.size
        val cur = viewModel.book?.durChapterIndex ?: 0
        binding.tvPre.isEnabled = cur > 0
        binding.tvNext.isEnabled = cur < total - 1
        binding.seekChapter.max = (total - 1).coerceAtLeast(0)
        if (!seekbarTracking) {
            binding.seekChapter.progress = cur.coerceIn(0, binding.seekChapter.max)
        }
    }

    private fun upNightThemeIcon() {
        val icon = if (AppConfig.isNightTheme) {
            R.drawable.ic_daytime
        } else {
            R.drawable.ic_brightness
        }
        binding.fabNightTheme.setImageResource(icon)
    }

    private fun renderMarkdown(markdown: String, generation: Int, scrollY: Int) {
        lifecycleScope.launch {
            val parsed = withContext(Dispatchers.Default) {
                markwon.toMarkdown(markdown)
            }
            if (generation != scrollRestoreGeneration) return@launch
            markwon.setParsedMarkdown(binding.tvContent, parsed)
            binding.tvContent.requestLayout()
            if (generation != scrollRestoreGeneration) return@launch
            restoreScrollPos(scrollY, generation)
        }
    }

    /**
     * 在内容高度尚未稳定时过早 [NestedScrollView.scrollTo] 会把 Y 钳在偏小的 maxY 上,
     * 书签从目录返回后常需再「刷新」才滚到位. 这里在全局布局变化时反复校正滚动,
     * 并在高度连续稳定后摘除监听, 再延迟一帧补滚以覆盖 Glide 等晚到的布局.
     */
    private fun restoreScrollPos(targetY: Int, generation: Int) {
        val sv = binding.scrollView
        removeScrollRestoreLayoutListener()

        if (generation != scrollRestoreGeneration) return

        if (targetY <= 0) {
            sv.scrollTo(0, 0)
            return
        }

        var invocations = 0
        var prevChildHeight = -1
        var stableCount = 0

        val listener = object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                invocations++
                if (generation != scrollRestoreGeneration) {
                    removeScrollRestoreLayoutListener()
                    return
                }
                val child = sv.getChildAt(0)
                if (child == null) {
                    if (invocations >= 40) removeScrollRestoreLayoutListener()
                    return
                }
                val ch = child.height
                if (ch == prevChildHeight && ch > 0) {
                    stableCount++
                } else {
                    stableCount = 0
                }
                prevChildHeight = ch

                val maxY = (ch - sv.height).coerceAtLeast(0)
                val y = targetY.coerceAtMost(maxY)
                sv.scrollTo(0, y)

                val timedOut = invocations >= 80
                val stableEnough = stableCount >= 3 && ch > 0
                if (timedOut || stableEnough) {
                    removeScrollRestoreLayoutListener()
                    scheduleScrollRestoreRetry(sv, targetY, generation, delayMs = 48L)
                    scheduleScrollRestoreRetry(sv, targetY, generation, delayMs = 220L)
                    scheduleScrollRestoreRetry(sv, targetY, generation, delayMs = 420L)
                }
            }
        }
        scrollRestoreLayoutListener = listener
        sv.viewTreeObserver.addOnGlobalLayoutListener(listener)
        sv.post {
            if (generation != scrollRestoreGeneration) return@post
            val child = sv.getChildAt(0)
            if (child != null && child.height > 0) {
                val maxY = (child.height - sv.height).coerceAtLeast(0)
                sv.scrollTo(0, targetY.coerceAtMost(maxY))
            }
            sv.requestLayout()
        }
    }

    private fun scheduleScrollRestoreRetry(
        sv: NestedScrollView,
        targetY: Int,
        generation: Int,
        delayMs: Long
    ) {
        lifecycleScope.launch {
            delay(delayMs)
            if (generation != scrollRestoreGeneration || isFinishing) return@launch
            val child = sv.getChildAt(0) ?: return@launch
            val maxY = (child.height - sv.height).coerceAtLeast(0)
            sv.scrollTo(0, targetY.coerceAtMost(maxY))
        }
    }

    private fun openTocActivity() {
        val bookUrl = viewModel.book?.bookUrl ?: return
        tocActivity.launch(bookUrl)
    }

    private fun createBookmark() {
        val book = viewModel.book ?: return
        val chapters = viewModel.chapters
        val curIndex = book.durChapterIndex.coerceIn(0, (chapters.size - 1).coerceAtLeast(0))
        val chapter = chapters.getOrNull(curIndex)
        if (chapter == null) {
            toastOnUi(R.string.create_bookmark_error)
            return
        }
        val snippet = captureVisibleSnippet().ifBlank { chapter.title }
        val bookmark: Bookmark = book.createBookMark().apply {
            chapterIndex = curIndex
            chapterPos = binding.scrollView.scrollY
            chapterName = chapter.title
            bookText = snippet
        }
        showDialogFragment(BookmarkDialog(bookmark))
    }

    /**
     * 取屏幕最上方可见的一段正文文本作为书签预览.
     * - 若可见顶部还在章节标题/顶部留白区域, 用章节标题兜底
     * - 优先按"句子"截断: 在常见句末标点处停止, 最长不超过 ~80 个字符
     */
    private fun captureVisibleSnippet(): String {
        val scroll = binding.scrollView
        val tv = binding.tvContent
        val layout = tv.layout ?: return ""

        val scrollLoc = IntArray(2).also { scroll.getLocationInWindow(it) }
        val tvLoc = IntArray(2).also { tv.getLocationInWindow(it) }
        // 屏幕上 "内容可见区域顶端" 在窗口坐标系中的 Y
        val visibleTopOnScreen = scrollLoc[1] + scroll.paddingTop
        // 它落在 tvContent 内的相对 Y; 负值表示还在 tvContent 之上 (章节标题/顶部留白)
        val offsetInTv = visibleTopOnScreen - tvLoc[1]
        if (offsetInTv < 0 || offsetInTv >= tv.height) {
            return ""
        }

        val line = layout.getLineForVertical(offsetInTv)
        val text = tv.text ?: return ""
        val start = layout.getLineStart(line).coerceAtMost(text.length)
        if (start >= text.length) return ""

        val maxLen = 80
        val end = minOf(start + maxLen * 2, text.length)
        var snippet = text.subSequence(start, end).toString().trim()
        if (snippet.length > maxLen) {
            val cutCandidates = listOf('。', '！', '？', '；', '\n', '.', '!', '?', ';')
            val cut = cutCandidates
                .map { snippet.indexOf(it).let { idx -> if (idx in 1..maxLen) idx + 1 else -1 } }
                .filter { it > 0 }
                .minOrNull()
                ?: maxLen
            snippet = snippet.substring(0, cut).trim()
        }
        return snippet
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.md_read, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_md_bookmark -> createBookmark()
            R.id.menu_md_refresh_toc -> {
                binding.refreshLayout.isRefreshing = true
                viewModel.refreshChapters()
            }
            R.id.menu_md_help -> showHelp("mdReadHelp")
        }
        return super.onCompatOptionsItemSelected(item)
    }

    override fun onPause() {
        super.onPause()
        viewModel.saveScrollPos(binding.scrollView.scrollY)
    }

    override fun onDestroy() {
        removeScrollRestoreLayoutListener()
        super.onDestroy()
    }

    /**
     * 拦截全局触摸事件:
     * - 屏幕中间区域内的"无位移短按" → 切换顶部/底部浮动菜单的显隐
     * - 横向长滑动 → 向左翻下一章 / 向右翻上一章
     *
     * [super.dispatchTouchEvent] 始终被调用, 因此正常的滚动、文字选择、
     * 长按菜单等行为都不会受影响.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = ev.x
                touchDownY = ev.y
                touchDownTime = ev.eventTime
                touchMoved = false
                chapterSwipeTriggered = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!touchMoved) {
                    val dx = abs(ev.x - touchDownX)
                    val dy = abs(ev.y - touchDownY)
                    if (dx > touchSlop || dy > touchSlop) {
                        touchMoved = true
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                val duration = ev.eventTime - touchDownTime
                val rawDx = ev.x - touchDownX
                val rawDy = ev.y - touchDownY
                val isChapterSwipe =
                    abs(rawDx) >= horizontalSwipeThreshold
                        && abs(rawDx) > abs(rawDy) * 2
                if (isChapterSwipe) {
                    chapterSwipeTriggered = true
                    if (rawDx < 0) goToNextChapter() else goToPrevChapter()
                } else if (!touchMoved && duration < tapTimeout
                    && isInCenterTapZone(ev.x, ev.y)
                ) {
                    setMenuVisible(!menuVisible)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                touchMoved = true
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun goToPrevChapter() {
        val cur = viewModel.book?.durChapterIndex ?: return
        if (cur > 0) viewModel.loadChapter(cur - 1)
        else toastOnUi(R.string.no_prev_chapter)
    }

    private fun goToNextChapter() {
        val cur = viewModel.book?.durChapterIndex ?: return
        val total = viewModel.chapters.size
        if (cur < total - 1) viewModel.loadChapter(cur + 1)
        else toastOnUi(R.string.no_next_chapter)
    }

    /** 把屏幕宽高均分成 3x3, 中间那一格是菜单触发区. */
    private fun isInCenterTapZone(x: Float, y: Float): Boolean {
        val rootW = binding.rootLayout.width.takeIf { it > 0 }
            ?: window.decorView.width
        val rootH = binding.rootLayout.height.takeIf { it > 0 }
            ?: window.decorView.height
        if (rootW <= 0 || rootH <= 0) return false
        val xMin = rootW / 3f
        val xMax = rootW - xMin
        val yMin = rootH / 3f
        val yMax = rootH - yMin
        return x in xMin..xMax && y in yMin..yMax
    }

    private fun setMenuVisible(visible: Boolean, animate: Boolean = true) {
        if (menuVisible == visible) return
        menuVisible = visible
        val titleBar = binding.titleBar
        val bottomMenu = binding.bottomMenu
        val fab = binding.fabNightTheme
        titleBar.animate().cancel()
        bottomMenu.animate().cancel()
        fab.animate().cancel()
        // 悬浮 FAB 跟随底栏一起上下移动 + 淡入淡出
        val fabOffset = (bottomMenu.height + fab.height).toFloat()
        if (!animate) {
            titleBar.translationY = if (visible) 0f else -titleBar.height.toFloat()
            titleBar.alpha = if (visible) 1f else 0f
            bottomMenu.translationY = if (visible) 0f else bottomMenu.height.toFloat()
            bottomMenu.alpha = if (visible) 1f else 0f
            fab.translationY = if (visible) 0f else fabOffset
            fab.alpha = if (visible) 1f else 0f
            upNavigationBarColor()
            return
        }
        val duration = 200L
        if (visible) {
            // 显示功能栏: 立刻让小白条与底栏同色, 与动画同步出现
            upNavigationBarColor()
        }
        titleBar.animate()
            .translationY(if (visible) 0f else -titleBar.height.toFloat())
            .alpha(if (visible) 1f else 0f)
            .setDuration(duration)
            .start()
        bottomMenu.animate()
            .translationY(if (visible) 0f else bottomMenu.height.toFloat())
            .alpha(if (visible) 1f else 0f)
            .setDuration(duration)
            .withEndAction {
                // 隐藏动画结束后再切小白条颜色; 若期间又打开了菜单则不再覆盖
                if (menuVisible) return@withEndAction
                upNavigationBarColor()
            }
            .start()
        fab.animate()
            .translationY(if (visible) 0f else fabOffset)
            .alpha(if (visible) 1f else 0f)
            .setDuration(duration)
            .start()
    }
}
