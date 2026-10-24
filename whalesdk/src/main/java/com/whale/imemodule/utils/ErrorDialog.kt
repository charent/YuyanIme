package com.whale.imemodule.utils

import android.app.AlertDialog
import android.content.Context
import androidx.annotation.StringRes
import com.whale.imemodule.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun Context.importErrorDialog(message: String) {
    withContext(Dispatchers.Main.immediate) {
        AlertDialog.Builder(this@importErrorDialog)
            .setTitle(R.string.import_error)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .show()
    }
}

suspend fun Context.importErrorDialog(t: Throwable) {
    importErrorDialog(t.localizedMessage ?: t.stackTraceToString())
}

suspend fun Context.importErrorDialog(@StringRes resId: Int, vararg formatArgs: Any?) {
    importErrorDialog(getString(resId, formatArgs))
}
