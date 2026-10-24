package com.whale.imemodule.ui.setup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.whale.imemodule.R
import com.whale.imemodule.compose.settings.SettingsTheme
import com.whale.imemodule.prefs.AppPrefs
import com.whale.imemodule.ui.activity.SettingsActivity
import com.whale.imemodule.utils.InputMethodUtil
import com.whale.imemodule.utils.notificationManager
import com.whale.imemodule.utils.startActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 启用引导（Phase 4 起为 Compose）。
 *
 * 页面内容与判定仍由 [SetupPage] 提供（纯逻辑，未迁移），
 * ViewPager2 + FragmentStateAdapter 换成 `HorizontalPager`。
 * 代码裁剪/输入法选择等动作直接走 `SetupPage.getButtonAction`。
 */
class SetupActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SettingsTheme {
                SetupScreen()
            }
        }
        createNotificationChannel()
    }

    override fun onPause() {
        if (SetupPage.hasUndonePage()) {
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_sdk_launcher_transparent)
                .setContentTitle(getText(R.string.app_name))
                .setContentText(getText(R.string.setup_keyboard))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(
                    PendingIntent.getActivity(
                        this,
                        0,
                        Intent(this, javaClass),
                        PendingIntent.FLAG_IMMUTABLE,
                    )
                )
                .setAutoCancel(true)
                .build()
                .let { notificationManager.notify(NOTIFY_ID, it) }
        }
        super.onPause()
    }

    override fun onResume() {
        notificationManager.cancel(NOTIFY_ID)
        super.onResume()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getText(R.string.setup_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = CHANNEL_ID }
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "setup"
        private const val NOTIFY_ID = 233
        fun shouldShowUp() = SetupPage.hasUndonePage()
    }
}

@Composable
private fun SetupScreen() {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val pages = remember { SetupPage.entries }
    val pagerState = rememberPagerState(
        initialPage = (SetupPage.firstUndonePage()?.ordinal ?: 0).coerceIn(0, pages.size - 1),
        pageCount = { pages.size },
    )
    var doneMask by remember { mutableStateOf(pages.map { it.isDone() }) }
    val allDone = doneMask.all { it }
    val scope = rememberCoroutineScope()

    // 完成状态的刷新时机 = 旧实现的 onResume + onWindowFocusChanged：
    // 从系统「启用输入法」设置页 / 输入法选择器返回后重估。
    // 注意不能只挂在 ON_RESUME：选择器关闭时系统写回默认输入法可能晚于我们的
    // resume，表现为「选完回来按钮不变」。这里聚焦恢复时同样触发，并在
    // LaunchedEffect 里延迟补刷几次兜底。
    var stateTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) stateTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val view = LocalView.current
    DisposableEffect(view) {
        val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            if (hasFocus) stateTick++
        }
        view.viewTreeObserver.addOnWindowFocusChangeListener(focusListener)
        onDispose { view.viewTreeObserver.removeOnWindowFocusChangeListener(focusListener) }
    }
    LaunchedEffect(stateTick) {
        if (stateTick == 0) return@LaunchedEffect
        repeat(4) {
            doneMask = pages.map { it.isDone() }
            delay(250)
        }
    }

    // onStart 的三条短路：未同意隐私 → 去设置页；已选输入法 → 关闭；已启用 → 直接到最后一页
    LaunchedEffect(Unit) {
        if (!AppPrefs.getInstance().internal.privacyPolicySure.getValue()) {
            context.startActivity<SettingsActivity>()
            activity?.finish()
        } else if (InputMethodUtil.isSelected()) {
            activity?.finish()
        } else if (InputMethodUtil.isEnabled()) {
            pagerState.scrollToPage(pages.size - 1)
        }
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { index ->
                val page = pages[index]
                val hint = when (page) {
                    SetupPage.Enable -> R.string.enable_ime_hint
                    SetupPage.Select -> R.string.select_ime_hint
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = AnnotatedString.fromHtml(
                            context.getString(hint, context.getString(R.string.app_name))
                        ),
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(24.dp))
                    if (doneMask[index]) {
                        Text(
                            text = context.getString(R.string.done),
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 16.sp,
                        )
                    } else {
                        Button(onClick = { page.getButtonAction(context) }) {
                            Text(page.getButtonText(context).toString())
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (pagerState.currentPage > 0) {
                    OutlinedButton(
                        onClick = { scope.launch { pagerState.scrollToPage(pagerState.currentPage - 1) } },
                        modifier = Modifier.weight(1f),
                    ) { Text(context.getString(R.string.prev)) }
                }
                val isLast = pagerState.currentPage == pages.size - 1
                if (!isLast || allDone) {
                    Button(
                        onClick = {
                            if (!isLast) scope.launch { pagerState.scrollToPage(pagerState.currentPage + 1) }
                            else activity?.finish()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(context.getString(if (isLast) R.string.done else R.string.next))
                    }
                }
            }
        }
    }
}
