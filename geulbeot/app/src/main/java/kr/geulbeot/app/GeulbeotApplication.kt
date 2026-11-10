package kr.geulbeot.app

import android.app.Application
import kr.geulbeot.app.data.DocumentStore
import kr.geulbeot.app.data.RecentDocuments
import kr.geulbeot.app.shortcut.Shortcuts

class GeulbeotApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Exported files and downloaded updates are working files, not documents. Clearing them at
        // launch keeps the app's cache from growing without bound and removes copies of the user's
        // documents that no longer need to exist.
        // Both of these touch the disk, and neither has to finish before the first frame.
        Thread {
            DocumentStore(this).clearExports()
            Shortcuts.publishRecent(this, RecentDocuments(this).load())
        }.apply { priority = Thread.MIN_PRIORITY }.start()
    }
}
