import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// ---- Versioning (docs/UPDATER.md §1.2) ------------------------------------------------------------
// -PversionName=X.Y.Z[-rc.N] (no leading "v"). Missing -> 0.0.0-dev / code 1. Invalid -> configuration fails.
// versionCode = X*1_000_000 + Y*10_000 + Z*100 + (final ? 99 : N). Keep in sync with tools/version-code.sh.
val versionGrammar = "^(\\d+)\\.(\\d+)\\.(\\d+)(?:-rc\\.(\\d+))?$"

fun versionFromName(name: String): Pair<String, Int> {
    fun bad(why: String): Nothing = throw GradleException(
        "Invalid -PversionName='$name' ($why). Expected X.Y.Z or X.Y.Z-rc.N matching $versionGrammar " +
            "with X <= 2000, Y,Z <= 99, 1 <= N <= 98 (no leading 'v')."
    )
    val m = Regex(versionGrammar).matchEntire(name) ?: bad("does not match the grammar")
    fun num(i: Int) = m.groupValues[i].toIntOrNull() ?: bad("number too large")
    val (x, y, z) = Triple(num(1), num(2), num(3))
    if (x > 2000) bad("X > 2000")
    if (y > 99 || z > 99) bad("Y or Z > 99")
    val pre = m.groups[4]?.let { num(4) }
    if (pre != null && pre !in 1..98) bad("rc number outside 1..98")
    return name to (x * 1_000_000 + y * 10_000 + z * 100 + (pre ?: 99))
}

val (appVersionName, appVersionCode) =
    providers.gradleProperty("versionName").orNull?.let(::versionFromName) ?: ("0.0.0-dev" to 1)

// Self-test of the §1.2 vectors: ./gradlew :app:checkVersionVectors
tasks.register("checkVersionVectors") {
    group = "verification"
    description = "Checks versionFromName against the docs/UPDATER.md §1.2 test vectors."
    notCompatibleWithConfigurationCache("calls a build-script function at execution time")
    doLast {
        val vectors = mapOf(
            "0.1.0" to 10099, "0.2.0-rc.1" to 20001, "0.2.0-rc.12" to 20012,
            "0.2.0" to 20099, "1.0.0" to 1000099, "1.2.3" to 1020399,
            "2000.99.99" to 2000999999, "0.0.1-rc.98" to 198,
        )
        vectors.forEach { (name, code) ->
            val got = versionFromName(name)
            check(got == name to code) { "versionFromName($name) = $got, expected ${name to code}" }
        }
        listOf("v1.0.0", "1.0", "1.0.0-rc.0", "1.0.0-rc.99", "1.0.0-rc", "1.0.0-beta.1", "1.100.0", "1.0.100",
            "2001.0.0", "99999999999.0.0", "", "1.0.0 ").forEach { bad ->
            check(runCatching { versionFromName(bad) }.exceptionOrNull() is GradleException) {
                "versionFromName('$bad') should have been rejected"
            }
        }
        println("version vectors OK")
    }
}

// ---- Release signing (docs/UPDATER.md §1.4) -------------------------------------------------------
// Resolution order, first hit wins: OPENFY_KEYSTORE_* env vars, then $OPENFY_KEYSTORE_PROPERTIES
// (default ~/.config/openfy/keystore.properties), then unsigned (fails if -PrequireSigning=true).
class KeystoreConfig(val storeFile: String, val storePassword: String, val keyAlias: String, val keyPassword: String)

fun loadKeystoreConfig(): KeystoreConfig? {
    fun fail(msg: String): Nothing = throw GradleException(msg)
    val env = System.getenv()
    val names = listOf("OPENFY_KEYSTORE_FILE", "OPENFY_KEYSTORE_PASSWORD", "OPENFY_KEY_ALIAS", "OPENFY_KEY_PASSWORD")
    if (names.any { !env[it].isNullOrEmpty() }) {
        val missing = names.filter { env[it].isNullOrEmpty() }
        if (missing.isNotEmpty()) fail("Incomplete release signing env: missing ${missing.joinToString()}.")
        return KeystoreConfig(env.getValue(names[0]), env.getValue(names[1]), env.getValue(names[2]), env.getValue(names[3]))
    }
    val propsPath = env["OPENFY_KEYSTORE_PROPERTIES"]?.takeIf { it.isNotEmpty() }
        ?: "${System.getProperty("user.home")}/.config/openfy/keystore.properties"
    val propsFile = File(propsPath)
    if (!propsFile.isFile) return null
    val props = Properties().apply { propsFile.inputStream().use(::load) }
    val keys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    val missing = keys.filter { props.getProperty(it).isNullOrEmpty() }
    if (missing.isNotEmpty()) fail("$propsPath is missing ${missing.joinToString()}.")
    return KeystoreConfig(props.getProperty(keys[0]), props.getProperty(keys[1]), props.getProperty(keys[2]), props.getProperty(keys[3]))
}

val keystoreConfig = loadKeystoreConfig()
if (keystoreConfig == null && providers.gradleProperty("requireSigning").orNull == "true") {
    throw GradleException(
        "Release signing is required (-PrequireSigning=true) but no keystore was configured. See docs/UPDATER.md section 1.4 and docs/RELEASING.md."
    )
}

android {
    namespace = "dev.openfy"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.openfy"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (keystoreConfig != null) {
            create("release") {
                storeFile = file(keystoreConfig.storeFile)
                storePassword = keystoreConfig.storePassword
                keyAlias = keystoreConfig.keyAlias
                keyPassword = keystoreConfig.keyPassword
                // minSdk 26: v2+v3 are enough; v1 (JAR) signing is legacy.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = if (keystoreConfig != null) signingConfigs.getByName("release") else null
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.media)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.json.test)
}
