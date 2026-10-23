package com.yuyan.imemodule.compose.settings.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuyan.imemodule.R
import com.yuyan.imemodule.compose.settings.SettingsDest
import com.yuyan.imemodule.compose.settings.SettingsPage
import com.yuyan.imemodule.compose.settings.string
import com.yuyan.imemodule.database.DataBaseKT
import com.yuyan.imemodule.database.entry.SideSymbol
import com.yuyan.imemodule.keyboard.KeyboardManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 侧边栏符号（对应旧 `SidebarSymbolFragment` + `PrefixSettingsFragment`）。
 *
 * 结构从 ViewPager2 + 两个 Fragment 换成 `TabRow` + 同一份状态；
 * 拖拽排序换成上/下移按钮（Compose 里拖拽需要额外依赖，功能等价且更稳）。
 * 数据仍写 `side_symbol` 表，写入时机与旧实现一致：离开页面时整体覆盖。
 */
@Composable
fun SidebarSymbolScreen(onBack: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    val types = remember { listOf("pinyin", "number") }
    val lists = remember {
        types.map { type ->
            val dao = DataBaseKT.instance.sideSymbolDao()
            val rows = if (type == "pinyin") dao.getAllSideSymbolPinyin() else dao.getAllSideSymbolNumber()
            mutableStateListOf<SideSymbol>().apply { addAll(rows) }
        }
    }
    val scope = rememberCoroutineScope()
    val save = {
        scope.launch(Dispatchers.IO) {
            types.forEachIndexed { index, type ->
                val dao = DataBaseKT.instance.sideSymbolDao()
                dao.deleteAll(type)
                dao.insertAll(lists[index].filter { it.symbolKey.isNotBlank() })
            }
            withContext(Dispatchers.Main) { KeyboardManager.instance.clearKeyboard() }
        }
        Unit
    }

    DisposableEffect(Unit) {
        onDispose { save() }
    }

    val current = lists[tab]

    SettingsPage(
        title = string(SettingsDest.Sidebar.title),
        onBack = onBack,
        actions = {
            TextButton(onClick = {
                current.add(SideSymbol("", "", type = types[tab]))
            }) { Text(string(R.string.skb_prefix_add)) }
        },
    ) {
        TabRow(selectedTabIndex = tab) {
            Tab(
                selected = tab == 0,
                onClick = { tab = 0 },
                text = { Text(string(R.string.sidebar_symbol_pinyin)) },
            )
            Tab(
                selected = tab == 1,
                onClick = { tab = 1 },
                text = { Text(string(R.string.sidebar_symbol_number)) },
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = string(R.string.skb_prefix_show_tips),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = string(R.string.skb_prefix_commit_tips),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(2f),
            )
        }
        current.forEachIndexed { index, item ->
            // key 用稳定 id，避免输入过程中行复用错位
            androidx.compose.runtime.key(index) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = item.symbolKey,
                        onValueChange = { current[index] = item.copy(symbolKey = it) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = item.symbolValue,
                        onValueChange = { current[index] = item.copy(symbolValue = it) },
                        singleLine = true,
                        modifier = Modifier.weight(2f),
                    )
                    IconButton(
                        onClick = {
                            if (index > 0) {
                                val prev = current[index - 1]
                                current[index - 1] = item
                                current[index] = prev
                            }
                        },
                        enabled = index > 0,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_menu_arrow_up),
                            contentDescription = null,
                        )
                    }
                    IconButton(
                        onClick = { current.removeAt(index) },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_menu_delete),
                            contentDescription = null,
                        )
                    }
                }
            }
        }
        if (current.isEmpty()) {
            Text(
                text = string(R.string.skb_prefix_empty),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        TextButton(
            onClick = { save() },
            modifier = Modifier.padding(horizontal = 12.dp),
        ) { Text(string(R.string.save)) }
        Spacer(Modifier.height(24.dp))
    }
}
