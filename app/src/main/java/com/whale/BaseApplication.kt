package com.whale

import android.app.Application
import com.whale.imemodule.application.Launcher
import com.whale.imemodule.utils.CrashHandler

/**
 * Applicaiton入口
 * @since 2019/6/18
 */
class BaseApplication : Application(){
    override fun onCreate() {
        super.onCreate()
        CrashHandler.instance.init(this)
        Launcher.instance.initData(baseContext)
    }
}
