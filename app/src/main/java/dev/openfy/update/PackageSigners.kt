package dev.openfy.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build

/** [SignerSource] and [PackageFacts] over PackageManager (docs/UPDATER.md §2.3-2/3). */
@Suppress("DEPRECATION")
class PackageSigners(private val context: Context) : SignerSource {
    private val pm: PackageManager get() = context.packageManager

    override fun installed(): List<ByteArray> {
        val info = try {
            pm.getPackageInfo(context.packageName, flags())
        } catch (_: PackageManager.NameNotFoundException) {
            return emptyList()
        }
        return signersOf(info)
    }

    override fun archive(path: String): List<ByteArray>? = pm.getPackageArchiveInfo(path, flags())?.let(::signersOf)

    fun installedFacts(): PackageFacts {
        val info = pm.getPackageInfo(context.packageName, 0)
        return PackageFacts(info.packageName, versionCodeOf(info), info.versionName)
    }

    fun archiveFacts(path: String): PackageFacts? =
        pm.getPackageArchiveInfo(path, 0)?.let { PackageFacts(it.packageName, versionCodeOf(it), it.versionName) }

    private fun flags(): Int =
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        else PackageManager.GET_SIGNATURES

    /** 28+: `signingInfo.apkContentsSigners` (all signers), falling back to `signatures`; 26-27: `signatures`. */
    private fun signersOf(info: PackageInfo): List<ByteArray> {
        if (Build.VERSION.SDK_INT >= 28) {
            val signers = info.signingInfo?.apkContentsSigners
            if (!signers.isNullOrEmpty()) return signers.map { it.toByteArray() }
        }
        return info.signatures?.map { it.toByteArray() } ?: emptyList()
    }

    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
}
