package ru.autoclicker

import android.app.Application
import ru.autoclicker.data.Prefs

class AutoClickerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
    }
}
