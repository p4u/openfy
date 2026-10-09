package dev.openspotify.shell

import android.media.MediaDrm
import android.os.Build
import android.util.Log
import java.util.UUID

/** DESIGN §2.5: debug-only Widevine facts for the thesis; not used for any decision. */
object DrmDiagnostics {
    private val WIDEVINE: UUID = UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")

    fun log() {
        try {
            val supported = MediaDrm.isCryptoSchemeSupported(WIDEVINE)
            if (!supported) {
                Log.i(TAG, "drm: Widevine not supported")
                return
            }
            val drm = MediaDrm(WIDEVINE)
            try {
                Log.i(
                    TAG,
                    "drm: Widevine supported, securityLevel=${drm.getPropertyString("securityLevel")} " +
                        "version=${drm.getPropertyString(MediaDrm.PROPERTY_VERSION)} " +
                        "vendor=${drm.getPropertyString(MediaDrm.PROPERTY_VENDOR)}",
                )
            } finally {
                if (Build.VERSION.SDK_INT >= 28) drm.close() else @Suppress("DEPRECATION") drm.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "drm: diagnostics failed", e)
        }
    }
}
