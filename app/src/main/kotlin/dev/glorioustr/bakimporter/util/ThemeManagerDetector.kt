package dev.glorioustr.bakimporter.util

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import dev.glorioustr.bakimporter.backup.MtzToBakConverter
import dev.glorioustr.bakimporter.model.ThemeManagerInfo

object ThemeManagerDetector {

    fun detect(context: Context): ThemeManagerInfo {
        val pm = context.packageManager
        val packageName = ThemeManagerInfo.PACKAGE_NAME

        return try {
            val info: PackageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }

            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }

            val versionName = info.versionName ?: "Unknown"
            val isHyperOS = isHyperOSDevice()

            ThemeManagerInfo(
                isInstalled = true,
                versionName = versionName,
                versionCode = versionCode,
                isHyperOS = isHyperOS,
            )
        } catch (e: PackageManager.NameNotFoundException) {
            ThemeManagerInfo.notInstalled()
        }
    }

    fun backupManifestMetadata(context: Context): MtzToBakConverter.BackupManifestMetadata {
        val pm = context.packageManager
        val packageName = ThemeManagerInfo.PACKAGE_NAME
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(
                packageName,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    PackageManager.GET_SIGNING_CERTIFICATES
                } else {
                    PackageManager.GET_SIGNATURES
                },
            )
        }
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners.orEmpty().map { it.toCharsString() }
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures.orEmpty().map { it.toCharsString() }
        }
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { pm.getInstallSourceInfo(packageName).installingPackageName.orEmpty() }.getOrDefault("")
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(packageName).orEmpty()
        }
        return MtzToBakConverter.BackupManifestMetadata(
            platformSdk = Build.VERSION.SDK_INT,
            installerPackageName = installer,
            signatures = signatures,
        )
    }

    private fun isHyperOSDevice(): Boolean {
        val osVersion = getSystemProperty("ro.mi.os.version.name")
        val miuiVersion = getSystemProperty("ro.miui.ui.version.name")
        return osVersion.contains("OS", ignoreCase = true) || miuiVersion.contains("V14", ignoreCase = true)
    }

    private fun getSystemProperty(key: String): String {
        return try {
            val c = Class.forName("android.os.SystemProperties")
            val get = c.getMethod("get", String::class.java)
            get.invoke(c, key) as String
        } catch (e: Throwable) {
            ""
        }
    }
}
