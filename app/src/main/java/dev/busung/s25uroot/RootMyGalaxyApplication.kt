package dev.busung.s25uroot

import android.app.Application
import android.content.Context
import rikka.shizuku.ShizukuProvider

class RootMyGalaxyApplication : Application() {
    private var isShizukuProviderProcess = false

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)

        // The provider itself lives in the default app process. Auto Root uses
        // dedicated :autoroot_gate / :autoroot_exec processes, so every process
        // enables the API's built-in Binder sharing before components initialize.
        isShizukuProviderProcess = Application.getProcessName() == base.packageName
        ShizukuProvider.enableMultiProcessSupport(isShizukuProviderProcess)
    }

    override fun onCreate() {
        super.onCreate()

        if (!isShizukuProviderProcess) {
            // A secondary process can be created after Shizuku already delivered its
            // Binder to the provider process. Explicitly request that existing Binder
            // instead of waiting for a future broadcast that may never come.
            ShizukuProvider.requestBinderForNonProviderProcess(this)
        }
    }
}
