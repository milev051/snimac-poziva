package studio.room211.pozivi

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

object Obavestenja {
    const val KANAL_SNIMANJE = "snimanje"
    const val KANAL_PREPIS = "prepis"
    const val KANAL_AZURIRANJE = "azuriranje"
    const val ID_SNIMANJE = 1
    const val ID_AZURIRANJE = 2

    fun kanali(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(KANAL_SNIMANJE, "Snimanje poziva", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(KANAL_PREPIS, "Prepis poziva", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(KANAL_AZURIRANJE, "Ažuriranje aplikacije", NotificationManager.IMPORTANCE_DEFAULT))
    }
}
