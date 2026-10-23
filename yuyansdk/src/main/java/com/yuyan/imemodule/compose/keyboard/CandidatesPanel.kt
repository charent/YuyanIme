package com.yuyan.imemodule.compose.keyboard

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yuyan.imemodule.R
import com.yuyan.imemodule.compose.ImeController
import com.yuyan.imemodule.compose.LocalImeTheme
import com.yuyan.imemodule.service.DictDecoder
import com.yuyan.imemodule.utils.DevicesUtils

/**
 * 全屏候选列表（候选栏右侧箭头「更多」进入）。
 *
 * 旧实现是 `RecyclerView + GridLayoutManager`（`CandidatesContainer`），
 * 这里用 `LazyVerticalGrid` 等价复刻，点选直接上屏。
 */
@Composable
fun CandidatesPanel(controller: ImeController) {
    val theme = LocalImeTheme.current
    val textColor = Color(theme.keyTextColor)
    val candidateState by DictDecoder.candidateState.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 64.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp),
        ) {
            itemsIndexed(candidateState.candidates) { index, item ->
                Box(
                    modifier = Modifier
                        .height(40.dp)
                        .clickable {
                            DevicesUtils.tryPlayKeyDown()
                            DevicesUtils.tryVibrate()
                            controller.dispatcher.chooseAndUpdate(index)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = item.text,
                        color = textColor,
                        style = TextStyle(fontSize = 18.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        // 收起
        Image(
            painter = painterResource(R.drawable.ic_menu_arrow_up),
            contentDescription = null,
            colorFilter = ColorFilter.tint(textColor),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .size(24.dp)
                .clickable { controller.switchToInputKeyboard() },
        )
    }
}
