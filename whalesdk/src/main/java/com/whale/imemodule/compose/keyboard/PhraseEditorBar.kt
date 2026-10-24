package com.whale.imemodule.compose.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import com.whale.imemodule.R
import com.whale.imemodule.compose.ImeController
import com.whale.imemodule.compose.ImeUiState

/**
 * 「增加常用语」编辑栏。
 *
 * 旧实现是一个真实的 [android.widget.EditText]（`EditPhrasesView`）挂在键盘上方，
 * 键盘按键通过 `commitText/sendKeyEvent` 打进这个 EditText。
 * Compose 版把「当前编辑哪个输入框 + 文本内容」收进 [ImeUiState]，
 * 按键由 `ImeController.editPhrase` 直接改写字符串——语义一致，
 * 但省掉了「IME 窗口里再挂一个可聚焦 EditText」带来的焦点竞争。
 *
 * 视觉上是一块输入卡片 + 两个圆形按钮（取消 / 完成），完成键用强调色实心，
 * 和键盘回车键的语义保持一致。
 */
@Composable
fun PhraseEditorBar(controller: ImeController, uiState: ImeUiState) {
    val palette = LocalImePalette.current

    Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(14.dp))
                .background(palette.card.copy(alpha = 0.9f))
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                // 常用语正文
                Text(
                    text = if (uiState.phraseContent.isEmpty()) controller.string(R.string.add_phrases_input_hint)
                    else uiState.phraseContent + if (!uiState.phraseEditingQuickCode) "|" else "",
                    color = if (uiState.phraseContent.isEmpty()) palette.muted else palette.onSurface,
                    style = TextStyle(
                        fontSize = 16.sp,
                        fontWeight = if (uiState.phraseEditingQuickCode) FontWeight.Normal else FontWeight.Medium,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .imeClickable() { controller.setPhraseEditingQuickCode(false) },
                )
                // 快捷码（拼音首字母）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = controller.string(R.string.add_phrases_input_tips),
                        color = palette.muted,
                        style = TextStyle(fontSize = 11.sp),
                    )
                    Text(
                        text = uiState.phraseQuickCode + if (uiState.phraseEditingQuickCode) "|" else "",
                        color = palette.onSurface,
                        style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                        modifier = Modifier
                            .weight(1f)
                            .imeClickable() { controller.setPhraseEditingQuickCode(true) }
                            .padding(start = 4.dp),
                    )
                }
            }
        }
        // 取消
        ImeIconButton(
            iconRes = R.drawable.ic_menu_close,
            contentDescription = null,
            tint = palette.onSurface,
            size = 38.dp,
            iconSize = 22.dp,
            onClick = { controller.cancelAddPhrases() },
        )
        // 完成：强调色实心圆
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(palette.accent)
                .imeClickable() { controller.finishAddPhrases() },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_menu_done),
                contentDescription = null,
                colorFilter = ColorFilter.tint(palette.onAccent),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
