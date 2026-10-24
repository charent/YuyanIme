package com.whale.imemodule.compose.settings.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.whale.imemodule.BuildConfig
import com.whale.imemodule.R
import com.whale.imemodule.application.CustomConstant
import com.whale.imemodule.application.Launcher
import com.whale.imemodule.compose.settings.SettingsDest
import com.whale.imemodule.compose.settings.SettingsPage
import com.whale.imemodule.compose.settings.SettingsRow
import com.whale.imemodule.compose.settings.SettingsSection
import com.whale.imemodule.compose.settings.string
import com.whale.imemodule.entity.PrivacyPolicy
import com.whale.imemodule.prefs.AppPrefs
import com.whale.imemodule.ui.setup.SetupActivity
import com.whale.imemodule.utils.TimeUtils
import com.whale.imemodule.utils.startActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 关于页（对应旧 `AboutFragment`） */
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onPrivacy: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportTimestamp = remember { System.currentTimeMillis() }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { exportCrashLogs(it) }
                        ?: error("open failed")
                }.isSuccess
            }
            if (!ok) Toast.makeText(context, R.string.export_crash_log, Toast.LENGTH_SHORT).show()
        }
    }

    SettingsPage(title = string(SettingsDest.About.title), onBack = onBack) {
        SettingsRow(
            title = string(R.string.privacy_policy),
            showArrow = true,
            onClick = onPrivacy,
        )
        SettingsRow(
            title = string(R.string.source_code),
            summary = string(R.string.github_repo),
            showArrow = true,
            onClick = { open(context, CustomConstant.WHALE_IME_REPO) },
        )
        SettingsRow(
            title = string(R.string.license),
            summary = "GPL-3.0 license",
            showArrow = true,
            onClick = { open(context, CustomConstant.LICENSE_URL) },
        )

        SettingsSection(title = string(R.string.app_version)) {
            SettingsRow(
                title = string(R.string.version),
                summary = BuildConfig.versionName,
                showArrow = true,
                onClick = { open(context, "${CustomConstant.WHALE_IME_REPO}/releases/latest") },
            )
            SettingsRow(
                title = string(R.string.build_git_hash),
                summary = BuildConfig.AppCommitHead,
                showArrow = true,
                onClick = {
                    val commit = BuildConfig.AppCommitHead.substringBefore('-')
                    open(context, "${CustomConstant.WHALE_IME_REPO}/commit/$commit")
                },
            )
            SettingsRow(
                title = string(R.string.build_time),
                summary = BuildConfig.AppBuildTime,
            )
        }

        SettingsSection(title = string(R.string.app_log)) {
            SettingsRow(
                title = string(R.string.export_crash_log),
                showArrow = true,
                onClick = {
                    exportTimestamp = System.currentTimeMillis()
                    exportLauncher.launch(
                        "whaleIme_crash_log${TimeUtils.iso8601UTCDateTime(exportTimestamp)}.zip"
                    )
                },
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** 隐私政策（对应旧 `PrivacyPolicyFragment`，正文来自 `raw/privacypolicy.json`） */
@Composable
fun PrivacyPolicyScreen(
    onBack: (() -> Unit)?,
    onAccepted: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val policies = remember {
        runCatching {
            val json = context.resources.openRawResource(R.raw.privacypolicy)
                .bufferedReader().use { it.readText() }
            Json.decodeFromString<List<PrivacyPolicy>>(json)
        }.getOrDefault(emptyList())
    }
    val firstRun = remember { !AppPrefs.getInstance().internal.privacyPolicySure.getValue() }

    SettingsPage(title = string(SettingsDest.Privacy.title), onBack = onBack) {
        policies.forEach { policy ->
            ListItem(
                headlineContent = { Text(policy.name) },
                supportingContent = { Text(policy.description) },
            )
        }
        if (firstRun) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        AppPrefs.getInstance().internal.privacyPolicySure.setValue(true)
                        if (SetupActivity.shouldShowUp()) context.startActivity<SetupActivity>()
                        onAccepted?.invoke()
                    },
                ) { Text(string(R.string.privacy_policy_sure)) }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = { (context as? android.app.Activity)?.finish() },
                ) { Text(string(R.string.privacy_policy_cancel)) }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun open(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}

/** 把 `files/crash_logs` 打包成 zip（逻辑与旧 `AboutFragment.export` 等价） */
private fun exportCrashLogs(dest: java.io.OutputStream) {
    val outputDir = File(Launcher.instance.context.filesDir, "crash_logs")
    ZipOutputStream(dest.buffered()).use { zip ->
        outputDir.walkTopDown().forEach { f ->
            val related = f.relativeTo(outputDir)
            if (related.path.isNotEmpty()) {
                when {
                    f.isDirectory -> zip.putNextEntry(ZipEntry("${related.path}/"))
                    f.isFile -> {
                        zip.putNextEntry(ZipEntry(related.path))
                        f.inputStream().use { it.copyTo(zip) }
                    }
                }
            }
        }
        zip.closeEntry()
    }
}
