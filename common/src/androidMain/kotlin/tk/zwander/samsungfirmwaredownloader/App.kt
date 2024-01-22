package tk.zwander.samsungfirmwaredownloader

import android.annotation.SuppressLint
import android.app.Application
import com.bugsnag.android.Bugsnag
import tk.zwander.common.GradleConfig

class App : Application() {
    companion object {
        @SuppressLint("StaticFieldLeak")
        var instance: App? = null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        Bugsnag.start(this, GradleConfig.bugsnagAndroidApiKey)
    }
}