package dev.glorioustr.bakimporter.model

data class ThemeManagerInfo(
    val isInstalled: Boolean,
    val versionName: String,
    val versionCode: Long,
    val isHyperOS: Boolean,
    val packageName: String = PACKAGE_NAME,
) {
    companion object {
        const val PACKAGE_NAME = "com.android.thememanager"

        fun notInstalled() = ThemeManagerInfo(
            isInstalled = false,
            versionName = "Not installed",
            versionCode = 0L,
            isHyperOS = false,
        )
    }
}
