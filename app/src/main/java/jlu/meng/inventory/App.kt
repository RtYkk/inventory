package jlu.meng.inventory

import android.app.Application

class App : Application() {
    val db: AppDatabase by lazy { AppDatabase.instance(this) }
}