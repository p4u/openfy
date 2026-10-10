package dev.openfy.update

/**
 * Release version per docs/UPDATER.md §1.2: `X.Y.Z` or `X.Y.Z-rc.N` (1..98), plus the special `0.0.0-dev` that
 * local builds carry and that sorts below everything. Pure; no Android imports.
 */
data class Version(val major: Int, val minor: Int, val patch: Int, val rc: Int?, val dev: Boolean = false) :
    Comparable<Version> {

    override fun compareTo(other: Version): Int {
        if (dev || other.dev) return (if (dev) 0 else 1) - (if (other.dev) 0 else 1)
        major.compareTo(other.major).let { if (it != 0) return it }
        minor.compareTo(other.minor).let { if (it != 0) return it }
        patch.compareTo(other.patch).let { if (it != 0) return it }
        // A final release (rc == null) is above every rc of the same triple.
        return (rc ?: Int.MAX_VALUE).compareTo(other.rc ?: Int.MAX_VALUE)
    }

    override fun toString(): String = when {
        dev -> "0.0.0-dev"
        rc != null -> "$major.$minor.$patch-rc.$rc"
        else -> "$major.$minor.$patch"
    }

    companion object {
        val DEV = Version(0, 0, 0, null, dev = true)
        private const val DEV_NAME = "0.0.0-dev"
        private val GRAMMAR = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-rc\.(\d+))?$""")

        /** Strict parse; a leading `v` is accepted. Null if [text] is not in the grammar or out of range. */
        fun parse(text: String): Version? {
            if (text == DEV_NAME || text == "v$DEV_NAME") return DEV
            val m = GRAMMAR.matchEntire(text) ?: return null
            val major = m.groupValues[1].toIntOrNull() ?: return null
            val minor = m.groupValues[2].toIntOrNull() ?: return null
            val patch = m.groupValues[3].toIntOrNull() ?: return null
            val rc = if (m.groups[4] != null) (m.groupValues[4].toIntOrNull() ?: return null) else null
            if (major > 2000 || minor > 99 || patch > 99) return null
            if (rc != null && rc !in 1..98) return null
            return Version(major, minor, patch, rc)
        }

        /**
         * The running app's own `versionName`. Anything not in the grammar (an old PoC name such as `0.1.0-poc` or
         * `0.1.0-debug`) counts as [DEV], i.e. lowest, so the signer and versionCode checks decide.
         */
        fun parseInstalled(versionName: String): Version = parse(versionName) ?: DEV
    }
}
