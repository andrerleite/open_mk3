package org.openmk3.core

import android.app.Application
import org.openmk3.core.ui.HomePage
import org.openmk3.core.ui.Page
import org.openmk3.core.ui.Ui

/**
 * What distinguishes one Open MK3 app from another (Vanilla, full, or a personal build).
 * Each app sets this up in its Application.onCreate(); everything else lives in :core.
 */
object Edition {
    /** Name shown on the MK3 screens. */
    var title = "OPEN MK3"
        private set
    /** App label, e.g. "Open MK3 Vanilla" (from the manifest). */
    var appName = "Open MK3"
        private set
    /** App version (from the installed package). */
    var version = "?"
        private set
    /** Folder under Download/ for samples and exported logs. */
    var storageFolder = "OpenMK3"
        private set
    /** Full feature set (sound parameters, groups, ...). Vanilla keeps the essentials. */
    var full = false
        private set
    /** Builds the main page shown after setup and when leaving other pages. */
    var homePage: (Ui) -> Page = { HomePage(it) }
        private set

    fun init(
        app: Application,
        title: String,
        storageFolder: String = "OpenMK3",
        full: Boolean = false,
        homePage: ((Ui) -> Page)? = null,
    ) {
        this.title = title
        this.full = full
        this.storageFolder = storageFolder
        homePage?.let { this.homePage = it }
        appName = app.applicationInfo.loadLabel(app.packageManager).toString()
        version = try {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "?"
        } catch (_: Exception) {
            "?"
        }
    }
}
