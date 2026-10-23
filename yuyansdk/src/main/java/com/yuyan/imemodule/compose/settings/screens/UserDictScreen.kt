package com.yuyan.imemodule.compose.settings.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuyan.imemodule.R
import com.yuyan.imemodule.compose.settings.SettingsDest
import com.yuyan.imemodule.compose.settings.SettingsPage
import com.yuyan.imemodule.compose.settings.SettingsRow
import com.yuyan.imemodule.compose.settings.SettingsSection
import com.yuyan.imemodule.compose.settings.string
import com.yuyan.imemodule.database.entry.UserWord
import com.yuyan.imemodule.libs.pinyin4j.PinyinHelper
import com.yuyan.imemodule.manager.UserDictManager
import com.yuyan.imemodule.utils.TimeUtils
import com.yuyan.imemodule.utils.UserDictCodec
import com.yuyan.imemodule.utils.importErrorDialog
import com.yuyan.imemodule.utils.queryFileName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 用户词库（对应旧 `UserDictFragment`）：搜索 / 筛选 / 增删改 / 导入导出。
 *
 * 数据写操作一律走 [UserDictManager]（它同时维护内存索引，改完立即对输入生效），
 * 与旧实现一致；UI 从 RecyclerView + Adapter 换成 `LazyColumn`。
 */
@Composable
fun UserDictScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var keyword by remember { mutableStateOf("") }
    var source by remember { mutableIntStateOf(FILTER_ALL) }
    var items by remember { mutableStateOf<List<UserWord>>(emptyList()) }
    var total by remember { mutableIntStateOf(0) }
    var hasMore by remember { mutableStateOf(true) }
    var loading by remember { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<UserWord?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var pendingClear by remember { mutableStateOf<Boolean?>(null) } // true=仅学习词
    var pendingImport by remember {
        mutableStateOf<Pair<UserDictCodec.Parsed, UserDictManager.ImportResult>?>(null)
    }
    var exportTimestamp by remember { mutableStateOf(System.currentTimeMillis()) }

    fun reload() {
        generation++
        val gen = generation
        loading = true
        scope.launch {
            val page = withContext(Dispatchers.IO) { UserDictManager.page(keyword, source, PAGE_SIZE, 0) }
            if (gen != generation) return@launch
            items = page
            hasMore = page.size >= PAGE_SIZE
            loading = false
            total = withContext(Dispatchers.IO) { UserDictManager.count(keyword, source) }
        }
    }

    fun loadMore() {
        if (!hasMore || loading) return
        val gen = generation
        val offset = items.size
        loading = true
        scope.launch {
            val page = withContext(Dispatchers.IO) { UserDictManager.page(keyword, source, PAGE_SIZE, offset) }
            if (gen != generation) return@launch
            items = items + page
            hasMore = page.size >= PAGE_SIZE
            loading = false
        }
    }

    // 搜索防抖（与旧实现 250ms 一致）
    LaunchedEffect(keyword, source) {
        delay(SEARCH_DEBOUNCE_MS)
        reload()
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) handleImport(context, uri, scope) { pendingImport = it } }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val count = runCatching {
                withContext(Dispatchers.IO) {
                    val text = UserDictManager.exportText()
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(text.toByteArray(Charsets.UTF_8))
                    } ?: error("open failed")
                    UserDictManager.count()
                }
            }
            count.onSuccess {
                Toast.makeText(
                    context,
                    context.getString(R.string.user_dict_export_done, it),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            count.onFailure { context.importErrorDialog(it) }
        }
    }

    SettingsPage(
        title = string(SettingsDest.UserDict.title),
        onBack = onBack,
        scrollable = false,
        actions = {
            TextButton(onClick = {
                editing = null
                showEditor = true
            }) { Text(string(R.string.user_dict_add)) }
        },
    ) {
        OutlinedTextField(
            value = keyword,
            onValueChange = { keyword = it.trim() },
            singleLine = true,
            placeholder = { Text(string(R.string.user_dict_search_hint)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                R.string.user_dict_filter_all to FILTER_ALL,
                R.string.user_dict_filter_manual to UserWord.SOURCE_MANUAL,
                R.string.user_dict_filter_learned to UserWord.SOURCE_LEARNED,
            ).forEach { (label, value) ->
                val selected = source == value
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .clickable { source = value }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = string(label),
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            text = string(R.string.user_dict_count).format(total),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )

        val listState = rememberLazyListState()
        if (items.isEmpty() && !loading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = string(R.string.user_dict_empty),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                items(items, key = { "${it.word}|${it.code}" }) { word ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                editing = word
                                showEditor = true
                            }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = word.word, fontSize = 16.sp)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = word.code,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = if (word.source == UserWord.SOURCE_MANUAL)
                                    string(R.string.user_dict_filter_manual)
                                else string(R.string.user_dict_filter_learned),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (word.weight != 0) {
                            Text(
                                text = "${string(R.string.user_dict_weight)}: ${word.weight}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                if (hasMore) {
                    item {
                        LaunchedEffect(items.size) { loadMore() }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator() }
                    }
                }
            }
        }

        SettingsSection(title = string(R.string.user_dict)) {
            SettingsRow(
                title = string(R.string.user_dict_import),
                showArrow = true,
                onClick = { importLauncher.launch("*/*") },
            )
            SettingsRow(
                title = string(R.string.user_dict_export),
                showArrow = true,
                onClick = {
                    scope.launch {
                        val count = withContext(Dispatchers.IO) { UserDictManager.count() }
                        if (count == 0) {
                            Toast.makeText(
                                context,
                                R.string.user_dict_export_empty,
                                Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            Toast.makeText(
                                context,
                                R.string.user_dict_export_tips,
                                Toast.LENGTH_LONG,
                            ).show()
                            exportTimestamp = System.currentTimeMillis()
                            exportLauncher.launch(
                                "yuyan_userdict_${TimeUtils.iso8601UTCDateTime(exportTimestamp)}.txt"
                            )
                        }
                    }
                },
            )
            SettingsRow(
                title = string(R.string.user_dict_clear_learned),
                showArrow = true,
                onClick = { pendingClear = true },
            )
            SettingsRow(
                title = string(R.string.user_dict_clear_all),
                showArrow = true,
                onClick = { pendingClear = false },
            )
        }
    }

    if (showEditor) {
        UserWordEditorDialog(
            origin = editing,
            onDismiss = { showEditor = false },
            onSaved = {
                showEditor = false
                reload()
            },
        )
    }

    pendingClear?.let { learnedOnly ->
        AlertDialog(
            onDismissRequest = { pendingClear = null },
            title = { Text(string(R.string.user_dict)) },
            text = {
                Text(
                    string(
                        if (learnedOnly) R.string.user_dict_clear_learned_confirm
                        else R.string.user_dict_clear_all_confirm
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (learnedOnly) UserDictManager.clearLearned() else UserDictManager.clearAll()
                    pendingClear = null
                    reload()
                }) { Text(string(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingClear = null }) { Text(string(R.string.cancel)) }
            },
        )
    }

    pendingImport?.let { (parsed, preview) ->
        var overwrite by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text(string(R.string.user_dict_import_confirm_title)) },
            text = {
                Column {
                    Text(
                        string(R.string.user_dict_import_confirm)
                            .format(parsed.entries.size, preview.added, preview.updated, preview.failed)
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { overwrite = !overwrite },
                    ) {
                        Checkbox(checked = overwrite, onCheckedChange = { overwrite = it })
                        Text(string(R.string.user_dict_import_overwrite))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingImport = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            UserDictManager.applyImport(parsed, overwrite)
                        }
                        Toast.makeText(
                            context,
                            context.getString(
                                R.string.user_dict_import_done,
                                result.added + result.updated,
                            ),
                            Toast.LENGTH_SHORT,
                        ).show()
                        reload()
                    }
                }) { Text(string(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingImport = null }) { Text(string(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun UserWordEditorDialog(
    origin: UserWord?,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    var word by remember { mutableStateOf(origin?.word ?: "") }
    var code by remember { mutableStateOf(origin?.code ?: "") }
    var weight by remember { mutableStateOf((origin?.weight ?: 0).toString()) }
    var codeTouched by remember { mutableStateOf(origin != null) }

    fun toast(resId: Int) = Toast.makeText(context, resId, Toast.LENGTH_SHORT).show()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(string(if (origin == null) R.string.user_dict_add else R.string.user_dict_edit))
        },
        text = {
            Column {
                OutlinedTextField(
                    value = word,
                    onValueChange = { value ->
                        word = value
                        // 编码未手动改过时，用首字母自动填充（旧实现同样逻辑）
                        if (!codeTouched) {
                            code = PinyinHelper.getPinYinHeadChar(value).lowercase()
                                .take(UserDictCodec.MAX_CODE_LENGTH)
                        }
                    },
                    singleLine = true,
                    label = { Text(string(R.string.user_dict_word)) },
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = {
                        code = it
                        codeTouched = true
                    },
                    singleLine = true,
                    label = { Text(string(R.string.user_dict_code)) },
                    supportingText = { Text(string(R.string.user_dict_code_tips)) },
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = weight,
                    onValueChange = { weight = it.filter { c -> c.isDigit() || c == '-' } },
                    singleLine = true,
                    label = { Text(string(R.string.user_dict_weight)) },
                    supportingText = { Text(string(R.string.user_dict_weight_tips)) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val w = weight.toIntOrNull() ?: 0
                val result = if (origin == null) {
                    UserDictManager.add(word, code, w)
                } else {
                    UserDictManager.update(origin.word, origin.code, word, code, w)
                }
                when (result) {
                    UserDictManager.SaveResult.OK -> onSaved()
                    UserDictManager.SaveResult.EMPTY_WORD -> toast(R.string.user_dict_word_empty)
                    UserDictManager.SaveResult.INVALID_CODE -> toast(R.string.user_dict_code_invalid)
                    UserDictManager.SaveResult.DUPLICATE -> toast(R.string.user_dict_duplicate)
                }
            }) { Text(string(R.string.user_dict_save)) }
        },
        dismissButton = {
            Row {
                if (origin != null) {
                    TextButton(onClick = {
                        UserDictManager.delete(origin.word, origin.code)
                        onSaved()
                    }) { Text(string(R.string.user_dict_delete)) }
                }
                TextButton(onClick = onDismiss) { Text(string(R.string.cancel)) }
            }
        },
    )
}

/** 读取导入文件 → 解析 → 预览，结果交给调用方弹确认框 */
private fun handleImport(
    context: android.content.Context,
    uri: Uri,
    scope: kotlinx.coroutines.CoroutineScope,
    onPreview: (Pair<UserDictCodec.Parsed, UserDictManager.ImportResult>) -> Unit,
) {
    scope.launch {
        val loaded = runCatching {
            withContext(Dispatchers.IO) {
                val cr = context.contentResolver
                val name = cr.queryFileName(uri).orEmpty()
                if (name.isNotEmpty() && !name.endsWith(".txt", true) &&
                    !name.endsWith(".tsv", true) && !name.endsWith(".csv", true)
                ) {
                    throw IllegalStateException(context.getString(R.string.user_dict_file_invalid))
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
                            throw IllegalStateException(
                                context.getString(R.string.user_dict_file_invalid)
                            )
                        }
                        sb.append(buffer, 0, read)
                    }
                    sb.toString()
                } ?: throw IllegalStateException(context.getString(R.string.user_dict_file_invalid))
                val parsed = UserDictManager.parse(text)
                if (parsed.entries.isEmpty()) {
                    throw IllegalStateException(context.getString(R.string.user_dict_file_invalid))
                }
                if (parsed.entries.size > UserDictCodec.MAX_IMPORT_LINES) {
                    throw IllegalStateException(context.getString(R.string.user_dict_file_invalid))
                }
                parsed to UserDictManager.previewImport(parsed)
            }
        }
        loaded.onSuccess(onPreview)
        loaded.onFailure { context.importErrorDialog(it) }
    }
}

private const val FILTER_ALL = -1
private const val PAGE_SIZE = 100
private const val SEARCH_DEBOUNCE_MS = 250L
