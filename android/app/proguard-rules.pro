# Komponente iz manifesta i WorkManager poslovi prave se po imenu.
-keep class studio.room211.pozivi.** extends android.app.Service { *; }
-keep class studio.room211.pozivi.** extends android.content.BroadcastReceiver { *; }
-keep class studio.room211.pozivi.** extends androidx.work.ListenableWorker { *; }
