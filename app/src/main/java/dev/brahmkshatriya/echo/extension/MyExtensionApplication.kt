package dev.brahmkshatriya.echo.extension

import android.app.Application

class MyExtensionApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        DeezerExtension(applicationContext)
    }
}