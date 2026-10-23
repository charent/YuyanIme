package com.yuyan.imemodule.compose.keyboard

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuyan.imemodule.R
import com.yuyan.imemodule.compose.ImeController
import com.yuyan.imemodule.compose.ImeUiState
import com.yuyan.imemodule.compose.LocalImeTheme
import com.yuyan.imemodule.singleton.EnvironmentSingleton

/**
 * 「增加常用语」编辑栏。
 *
 * 旧实现是一个真实的 [android.widget.EditText]（`EditPhrasesView`）挂在键盘上方，
 * 键盘按键通过 `commitText/sendKeyEvent` 打进这个 EditText。
 * Compose 版把「当前编辑哪个输入框 + 文本内容」收进 [ImeUiState]，
 * 按键由 `ImeController.editPhrase` 直接改写字符串——语义一致，
 * 但省掉了「IME 窗口里再挂一个可聚焦 EditText」带来的焦点竞争。
 */
@Composable
fun PhraseEditorBar(controller: ImeController, uiState: ImeUiState) {
    val theme = LocalImeTheme.current
    val env = EnvironmentSingleton.instance
    val textColor = Color(theme.keyTextColor)

    Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            // 常用语正文
            Text(
                text = if (uiState.phraseContent.isEmpty()) controller.string(R.string.add_phrases_input_hint)
                else uiState.phraseContent + if (!uiState.phraseEditingQuickCode) "|" else "",
                color = if (uiState.phraseContent.isEmpty()) textColor.copy(alpha = 0.6f) else textColor,
                style = TextStyle(
                    fontSize = 16.sp,
                    fontWeight = if (uiState.phraseEditingQuickCode) FontWeight.Normal else FontWeight.Medium,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clickable { controller.setPhraseEditingQuickCode(false) },
            )
            // 快捷码（拼音首字母）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = controller.string(R.string.add_phrases_input_tips),
                    color = textColor.copy(alpha = 0.6f),
                    style = TextStyle(fontSize = 11.sp),
                )
                Text(
                    text = uiState.phraseQuickCode + if (uiState.phraseEditingQuickCode) "|" else "",
                    color = textColor,
                    style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                    modifier = Modifier
                        .weight(1f)
                        .clickable { controller.setPhraseEditingQuickCode(true) }
                        .padding(start = 4.dp),
                )
            }
        }
        ActionIcon(R.drawable.ic_menu_close, textColor) { controller.cancelAddPhrases() }
        ActionIcon(R.drawable.ic_menu_done, textColor) { controller.finishAddPhrases() }
    }
}

@Composable
private fun ActionIcon(res: Int, tint: Color, onClick: () -> Unit) {
    Image(
        painter = painterResource(res),
        contentDescription = null,
        colorFilter = ColorFilter.tint(tint),
        modifier = Modifier.size(30.dp).clickable { onClick() },
    )
}
