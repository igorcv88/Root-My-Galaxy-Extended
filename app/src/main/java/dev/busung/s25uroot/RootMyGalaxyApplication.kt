package dev.busung.s25uroot

import android.app.Application
import android.content.Context
import rikka.shizuku.ShizukuProvider

class RootMyGalaxyApplication : Application() {
    override fun attachBaseContext(base: Context) {
        // ShizukuProvider is hosted in the default app process. Secondary Auto Root
        // processes ask that provider process for its Binder through the API's built-in
        // multi-process bridge.
        ShizukuProvider.enableMultiProcessSupport(
            Application.getProcessName() == base.packageName,
        )
        super.attachBaseContext(base)
    }
}
