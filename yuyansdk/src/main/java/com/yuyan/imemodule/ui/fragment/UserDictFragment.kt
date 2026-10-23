package com.yuyan.imemodule.ui.fragment

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.MenuProvider
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.yuyan.imemodule.R
import com.yuyan.imemodule.adapter.UserDictAdapter
import com.yuyan.imemodule.adapter.resolveThemeColor
import com.yuyan.imemodule.database.entry.UserWord
import com.yuyan.imemodule.libs.pinyin4j.PinyinHelper
import com.yuyan.imemodule.manager.UserDictManager
import com.yuyan.imemodule.utils.TimeUtils
import com.yuyan.imemodule.utils.UserDictCodec
import com.yuyan.imemodule.utils.importErrorDialog
import com.yuyan.imemodule.utils.queryFileName
import com.yuyan.imemodule.view.widget.applyNavBarInsetsBottomPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.dimensions.dp

/**
 * 用户词库管理：搜索 / 筛选 / 增删改（含权重）/ 导入导出。
 *
 * 数据的写操作一律走 [UserDictManager]，它同时维护内存索引，改完立刻对输入生效。
 */
class UserDictFragment : Fragment() {

    private lateinit var adapter: UserDictAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var summaryView: TextView
    private val filterViews = mutableListOf<TextView>()

    private var keyword = ""
    private var source = FILTER_ALL
    private var hasMore = true
    private var loadingPage = false
    private var queryGeneration = 0
    private var exportTimestamp = System.currentTimeMillis()

    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null

    private lateinit var importLauncher: ActivityResultLauncher<String>
    private lateinit var exportLauncher: ActivityResultLauncher<String>

    private val ctx get() = requireContext()

    private val textPrimary get() = ctx.resolveThemeColor(android.R.attr.textColorPrimary)
    private val textSecondary get() = ctx.resolveThemeColor(android.R.attr.textColorSecondary)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        importLauncher =
            registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                if (uri != null) handleImport(uri)
            }
        exportLauncher =
            registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
                if (uri != null) handleExport(uri)
            }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        adapter = UserDictAdapter(ctx) { showEditDialog(it) }
        filterViews.clear()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
        }

        root.addView(buildSearchBar())
        root.addView(buildFilterRow())

        summaryView = TextView(ctx).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(textSecondary)
            setPadding(dp(20), 0, dp(20), dp(8))
        }
        root.addView(summaryView)

        recyclerView = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            setHasFixedSize(true)
            setItemAnimator(null)
            adapter = this@UserDictFragment.adapter
            applyNavBarInsetsBottomPadding()
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    if (dy <= 0) return
                    val lm = rv.layoutManager as? LinearLayoutManager ?: return
                    if (lm.findLastVisibleItemPosition() >= this@UserDictFragment.adapter.size - PRELOAD_DISTANCE) loadMore()
                }
            })
        }

        emptyView = TextView(ctx).apply {
            setText(R.string.user_dict_empty)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(textSecondary)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }

        val listContainer = FrameLayout(ctx)
        listContainer.addView(
            recyclerView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        listContainer.addView(
            emptyView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        root.addView(
            listContainer,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.user_dict_menu, menu)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean = when (menuItem.itemId) {
                R.id.user_dict_add -> {
                    showEditDialog(null)
                    true
                }

                R.id.user_dict_import -> {
                    importLauncher.launch("*/*")
                    true
                }

                R.id.user_dict_export -> {
                    startExport()
                    true
                }

                R.id.user_dict_clear_learned -> {
                    confirmClear(learnedOnly = true)
                    true
                }

                R.id.user_dict_clear_all -> {
                    confirmClear(learnedOnly = false)
                    true
                }

                else -> false
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
        selectFilter(0, reload = false)
        reload()
    }

    override fun onResume() {
        super.onResume()
        (activity as? AppCompatActivity)?.supportActionBar?.setTitle(R.string.user_dict)
    }

    override fun onDestroyView() {
        searchRunnable?.let { searchHandler.removeCallbacks(it) }
        super.onDestroyView()
    }

    // ------------------------------------------------------------------ 视图构建

    private fun buildSearchBar(): View {
        val wrapper = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(12), dp(16), dp(4))
        }
        val input = EditText(ctx).apply {
            setHint(R.string.user_dict_search_hint)
            setSingleLine()
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(textPrimary)
            setHintTextColor(textSecondary)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(0x12000000)
            }
            doOnTextChanged { text, _, _, _ ->
                val value = text?.toString()?.trim().orEmpty()
                if (value == keyword) return@doOnTextChanged
                keyword = value
                searchRunnable?.let { searchHandler.removeCallbacks(it) }
                val runnable = Runnable { reload() }
                searchRunnable = runnable
                searchHandler.postDelayed(runnable, SEARCH_DEBOUNCE_MS)
            }
        }
        wrapper.addView(
            input,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        return wrapper
    }

    private fun buildFilterRow(): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        listOf(
            R.string.user_dict_filter_all,
            R.string.user_dict_filter_manual,
            R.string.user_dict_filter_learned
        ).forEachIndexed { index, label ->
            val chip = TextView(ctx).apply {
                setText(label)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(14), dp(6), dp(14), dp(6))
                isClickable = true
                setOnClickListener { selectFilter(index) }
            }
            row.addView(chip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = ctx.dp(8) })
            filterViews += chip
        }
        return row
    }

    private fun selectFilter(index: Int, reload: Boolean = true) {
        source = when (index) {
            1 -> UserWord.SOURCE_MANUAL
            2 -> UserWord.SOURCE_LEARNED
            else -> FILTER_ALL
        }
        filterViews.forEachIndexed { i, chip ->
            val selected = i == index
            chip.setTextColor(if (selected) textPrimary else textSecondary)
            chip.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            chip.background = if (selected) GradientDrawable().apply {
                cornerRadius = ctx.dp(14).toFloat()
                setColor(0x14000000)
            } else null
        }
        if (reload) reload()
    }

    // ------------------------------------------------------------------ 数据加载

    private fun reload() {
        if (!isAdded || view == null) return
        queryGeneration++
        val generation = queryGeneration
        hasMore = true
        loadingPage = true
        viewLifecycleOwner.lifecycleScope.launch {
            val page = withContext(Dispatchers.IO) {
                UserDictManager.page(keyword, source, PAGE_SIZE, 0)
            }
            if (generation != queryGeneration || !isAdded) return@launch
            adapter.submit(page)
            hasMore = page.size >= PAGE_SIZE
            loadingPage = false
            refreshSummary()
            updateEmptyState()
        }
    }

    private fun loadMore() {
        if (!hasMore || loadingPage) return
        loadingPage = true
        val generation = queryGeneration
        val offset = adapter.size
        viewLifecycleOwner.lifecycleScope.launch {
            val page = withContext(Dispatchers.IO) {
                UserDictManager.page(keyword, source, PAGE_SIZE, offset)
            }
            if (generation != queryGeneration || !isAdded) return@launch
            adapter.append(page)
            hasMore = page.size >= PAGE_SIZE
            loadingPage = false
        }
    }

    private fun refreshSummary() {
        viewLifecycleOwner.lifecycleScope.launch {
            val total = withContext(Dispatchers.IO) { UserDictManager.count(keyword, source) }
            if (!isAdded) return@launch
            summaryView.text = getString(R.string.user_dict_count, total)
        }
    }

    private fun updateEmptyState() {
        val empty = adapter.size == 0
        emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        recyclerView.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun toast(@androidx.annotation.StringRes res: Int, vararg args: Any) {
        Toast.makeText(ctx, getString(res, *args), Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------ 编辑

    private fun showEditDialog(origin: UserWord?) {
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
        }
        val etWord = addField(container, R.string.user_dict_word, origin?.word ?: "")
        val etCode = addField(container, R.string.user_dict_code, origin?.code ?: "")
        addTip(container, R.string.user_dict_code_tips)
        val etWeight = addField(container, R.string.user_dict_weight, (origin?.weight ?: 0).toString()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        addTip(container, R.string.user_dict_weight_tips)

        var codeTouched = origin != null
        var autoFilling = false
        etCode.doOnTextChanged { _, _, _, _ -> if (!autoFilling) codeTouched = true }
        etWord.doOnTextChanged { text, _, _, _ ->
            if (codeTouched || origin != null) return@doOnTextChanged
            val auto = PinyinHelper.getPinYinHeadChar(text?.toString().orEmpty())
                .lowercase()
                .take(UserDictCodec.MAX_CODE_LENGTH)
            autoFilling = true
            etCode.setText(auto)
            etCode.setSelection(auto.length)
            autoFilling = false
        }

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(if (origin == null) R.string.user_dict_add else R.string.user_dict_edit)
            .setView(container)
            .setPositiveButton(R.string.user_dict_save, null)
            .setNegativeButton(R.string.cancel, null)
            .apply {
                if (origin != null) setNeutralButton(R.string.user_dict_delete, null)
            }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val word = etWord.text.toString()
                val code = etCode.text.toString()
                val weight = etWeight.text.toString().toIntOrNull() ?: 0
                val result = if (origin == null) {
                    UserDictManager.add(word, code, weight)
                } else {
                    UserDictManager.update(origin.word, origin.code, word, code, weight)
                }
                when (result) {
                    UserDictManager.SaveResult.OK -> {
                        dialog.dismiss()
                        reload()
                    }

                    UserDictManager.SaveResult.EMPTY_WORD -> toast(R.string.user_dict_word_empty)
                    UserDictManager.SaveResult.INVALID_CODE -> toast(R.string.user_dict_code_invalid)
                    UserDictManager.SaveResult.DUPLICATE -> toast(R.string.user_dict_duplicate)
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                if (origin == null) return@setOnClickListener
                AlertDialog.Builder(ctx)
                    .setMessage(getString(R.string.user_dict_delete_confirm, origin.word))
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        UserDictManager.delete(origin.word, origin.code)
                        dialog.dismiss()
                        reload()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
        dialog.show()
    }

    private fun addField(container: LinearLayout, labelRes: Int, initial: String): EditText {
        container.addView(TextView(ctx).apply {
            setText(labelRes)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(textSecondary)
            setPadding(0, dp(12), 0, dp(4))
        })
        val input = EditText(ctx).apply {
            setText(initial)
            setSingleLine()
            if (initial.isNotEmpty()) setSelection(initial.length)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(textPrimary)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(0x12000000)
            }
        }
        container.addView(
            input,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        return input
    }

    private fun addTip(container: LinearLayout, textRes: Int) {
        container.addView(TextView(ctx).apply {
            setText(textRes)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(textSecondary)
            setPadding(0, dp(4), 0, 0)
        })
    }

    // ------------------------------------------------------------------ 清空

    private fun confirmClear(learnedOnly: Boolean) {
        AlertDialog.Builder(ctx)
            .setTitle(R.string.user_dict)
            .setMessage(if (learnedOnly) R.string.user_dict_clear_learned_confirm else R.string.user_dict_clear_all_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (learnedOnly) UserDictManager.clearLearned() else UserDictManager.clearAll()
                reload()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ 导入 / 导出

    private fun handleImport(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            val loaded = runCatching {
                withContext(Dispatchers.IO) {
                    val cr = ctx.contentResolver
                    val name = cr.queryFileName(uri).orEmpty()
                    if (name.isNotEmpty() && !name.endsWith(".txt", true) && !name.endsWith(".tsv", true) &&
                        !name.endsWith(".csv", true)
                    ) {
                        throw IllegalStateException(getString(R.string.user_dict_file_invalid))
                    }
                    val text = cr.openInputStream(uri)?.bufferedReader()?.use { reader ->
                        val sb = StringBuilder()
                        val buffer = CharArray(8192)
                        var total = 0
                        while (true) {
                            val read = reader.read(buffer)
                            if (read <= 0) break
                            total += read
                            if (total > UserDictCodec.MAX_IMPORT_BYTES) {
                                throw IllegalStateException(getString(R.string.user_dict_file_invalid))
                            }
                            sb.append(buffer, 0, read)
                        }
                        sb.toString()
                    } ?: throw IllegalStateException(getString(R.string.user_dict_file_invalid))
                    val parsed = UserDictManager.parse(text)
                    if (parsed.entries.isEmpty()) throw IllegalStateException(getString(R.string.user_dict_file_invalid))
                    if (parsed.entries.size > UserDictCodec.MAX_IMPORT_LINES) {
                        throw IllegalStateException(getString(R.string.user_dict_file_invalid))
                    }
                    parsed to UserDictManager.previewImport(parsed)
                }
            }
            val pair = loaded.getOrElse {
                ctx.importErrorDialog(it)
                return@launch
            }
            val parsed = pair.first
            val preview = pair.second
            showImportConfirm(parsed, preview)
        }
    }

    private fun showImportConfirm(parsed: UserDictCodec.Parsed, preview: UserDictManager.ImportResult) {
        val overwrite = CheckBox(ctx).apply {
            setText(R.string.user_dict_import_overwrite)
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.user_dict_import_confirm_title)
            .setMessage(
                getString(
                    R.string.user_dict_import_confirm,
                    parsed.entries.size,
                    preview.added,
                    preview.updated,
                    preview.failed
                )
            )
            .setView(overwrite)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        UserDictManager.applyImport(parsed, overwrite.isChecked)
                    }
                    toast(R.string.user_dict_import_done, result.added + result.updated)
                    reload()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startExport() {
        val total = UserDictManager.count()
        if (total == 0) {
            toast(R.string.user_dict_export_empty)
            return
        }
        Toast.makeText(ctx, R.string.user_dict_export_tips, Toast.LENGTH_LONG).show()
        exportTimestamp = System.currentTimeMillis()
        exportLauncher.launch("yuyan_userdict_${TimeUtils.iso8601UTCDateTime(exportTimestamp)}.txt")
    }

    private fun handleExport(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            val count = runCatching {
                withContext(Dispatchers.IO) {
                    val text = UserDictManager.exportText()
                    ctx.contentResolver.openOutputStream(uri)?.use {
                        it.write(text.toByteArray(Charsets.UTF_8))
                    } ?: throw IllegalStateException(getString(R.string.user_dict_file_invalid))
                    UserDictManager.count()
                }
            }
            count.onSuccess { toast(R.string.user_dict_export_done, it) }
            count.onFailure { ctx.importErrorDialog(it) }
        }
    }

    private companion object {
        const val FILTER_ALL = -1
        const val PAGE_SIZE = 100
        const val PRELOAD_DISTANCE = 8
        const val SEARCH_DEBOUNCE_MS = 250L
    }
}
