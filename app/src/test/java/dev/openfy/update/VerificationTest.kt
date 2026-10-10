package dev.openfy.update

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Signer set equality, package/version checks, SHA-256 and the size cap. */
class VerificationTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun cert(n: Int) = ByteArray(32) { (it + n).toByte() }

    private class FakeSigners(val installed: List<ByteArray>, val archive: List<ByteArray>?) : SignerSource {
        override fun installed() = installed
        override fun archive(path: String) = archive
    }

    // region SignerCheck

    @Test
    fun equalSignerSetsMatchRegardlessOfOrder() {
        assertEquals(SignerCheck.Verdict.Match, SignerCheck.verdict(listOf(cert(1)), listOf(cert(1))))
        assertEquals(SignerCheck.Verdict.Match, SignerCheck.verdict(listOf(cert(1), cert(2)), listOf(cert(2), cert(1))))
        assertEquals(SignerCheck.Verdict.Match, SignerCheck.verdict(listOf(cert(1), cert(1)), listOf(cert(1))))
    }

    @Test
    fun differentSignerIsRefused() {
        assertTrue(SignerCheck.verdict(listOf(cert(1)), listOf(cert(2))) is SignerCheck.Verdict.Refused)
    }

    @Test
    fun subsetAndSupersetAreRefused() {
        assertTrue(SignerCheck.verdict(listOf(cert(1), cert(2)), listOf(cert(1))) is SignerCheck.Verdict.Refused)
        assertTrue(SignerCheck.verdict(listOf(cert(1)), listOf(cert(1), cert(2))) is SignerCheck.Verdict.Refused)
    }

    @Test
    fun emptyAndNullAreRefused() {
        assertTrue(SignerCheck.verdict(emptyList(), emptyList()) is SignerCheck.Verdict.Refused)
        assertTrue(SignerCheck.verdict(listOf(cert(1)), emptyList()) is SignerCheck.Verdict.Refused)
        assertTrue(SignerCheck.verdict(emptyList(), listOf(cert(1))) is SignerCheck.Verdict.Refused)
        assertTrue(SignerCheck.verdict(listOf(cert(1)), null) is SignerCheck.Verdict.Refused)
    }

    // endregion

    // region PreInstallCheck

    private val installed = PackageFacts("dev.openfy", 20099, "0.2.0")
    private val good = PackageFacts("dev.openfy", 20199, "0.2.1")
    private val same = FakeSigners(listOf(cert(1)), listOf(cert(1)))

    private fun refusal(
        archive: PackageFacts? = good,
        signers: SignerSource = same,
        installedFacts: PackageFacts = installed,
        expected: String = "0.2.1",
    ) = PreInstallCheck.refusal(installedFacts, archive, signers, "/x.apk", expected)

    @Test
    fun newerSamePackageSameSignerPasses() {
        assertNull(refusal())
    }

    @Test
    fun sameVersionCodeIsRefused() {
        assertTrue(refusal(archive = good.copy(versionCode = 20099))!!.contains("not newer"))
    }

    @Test
    fun downgradeIsRefused() {
        assertTrue(refusal(archive = good.copy(versionCode = 10099, versionName = "0.1.0"), expected = "0.1.0")!!.contains("Downgrade"))
    }

    @Test
    fun otherPackageIsRefused() {
        assertTrue(refusal(archive = good.copy(packageName = "dev.openfy.debug"))!!.contains("dev.openfy.debug"))
    }

    @Test
    fun versionNameMustMatchTheRelease() {
        assertTrue(refusal(expected = "0.2.2")!!.contains("0.2.2"))
        assertTrue(refusal(archive = good.copy(versionName = null)) != null)
    }

    @Test
    fun aDebugSuffixInTheArchiveNameIsNotTolerated() {
        assertTrue(refusal(archive = good.copy(versionName = "0.2.1-debug")) != null)
    }

    @Test
    fun signerMismatchIsRefusedWithAnExplanation() {
        val msg = refusal(signers = FakeSigners(listOf(cert(1)), listOf(cert(2))))
        assertEquals(SignerCheck.SIGNER_MISMATCH, msg)
    }

    @Test
    fun unreadableArchiveIsRefused() {
        assertEquals("The downloaded file is not a valid APK.", refusal(archive = null))
        assertTrue(refusal(signers = FakeSigners(listOf(cert(1)), null)) != null)
    }

    // endregion

    // region SHA-256

    @Test
    fun streamingDigestMatchesKnownVectors() {
        val abc = tmp.newFile("abc").apply { writeText("abc") }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.hexOf(abc))
        val empty = tmp.newFile("empty")
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.hexOf(empty))
    }

    @Test
    fun streamingDigestHandlesFilesLargerThanTheBuffer() {
        val big = File(tmp.root, "big").apply { writeBytes(ByteArray(300_000) { (it % 251).toByte() }) }
        val md = java.security.MessageDigest.getInstance("SHA-256")
        assertEquals(Sha256.hex(md.digest(big.readBytes())), Sha256.hexOf(big))
    }

    @Test
    fun digestFieldParsing() {
        val hex = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertEquals(hex, Sha256.fromDigestField("sha256:$hex"))
        assertEquals(hex, Sha256.fromDigestField("sha256:${hex.uppercase()}"))
        assertNull(Sha256.fromDigestField(null))
        assertNull(Sha256.fromDigestField(hex))
        assertNull(Sha256.fromDigestField("sha1:$hex"))
        assertNull(Sha256.fromDigestField("sha256:${hex.dropLast(1)}"))
    }

    @Test
    fun checksumFileParsing() {
        val hex = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertEquals(hex, Sha256.fromChecksumFile("$hex  openfy-0.2.0.apk\n"))
        assertEquals(hex, Sha256.fromChecksumFile("  ${hex.uppercase()}\n"))
        assertNull(Sha256.fromChecksumFile(""))
        assertNull(Sha256.fromChecksumFile("not a hash  openfy.apk"))
        assertNull(Sha256.fromChecksumFile("<html>${hex}</html>"))
    }

    // endregion

    // region size cap and bounded copy

    @Test
    fun declaredSizeCap() {
        assertNull(SizeCap.refusal(1))
        assertNull(SizeCap.refusal(SizeCap.MAX_APK_BYTES))
        assertTrue(SizeCap.refusal(SizeCap.MAX_APK_BYTES + 1) != null)
        assertTrue(SizeCap.refusal(0) != null)
        assertTrue(SizeCap.refusal(-5) != null)
    }

    @Test
    fun boundedCopyCopiesUpToTheLimitAndHashesWhatItWrote() {
        val data = ByteArray(20_000) { it.toByte() }
        val out = ByteArrayOutputStream()
        val md = Sha256.newDigest()
        val progress = mutableListOf<Long>()
        val n = copyBounded(ByteArrayInputStream(data), out, 20_000, md) { progress += it }
        assertEquals(20_000L, n)
        assertTrue(out.toByteArray().contentEquals(data))
        assertEquals(Sha256.hex(Sha256.newDigest().digest(data)), Sha256.hex(md.digest()))
        assertEquals(20_000L, progress.last())
    }

    @Test
    fun boundedCopyAbortsWhenTheStreamExceedsTheLimit() {
        val out = ByteArrayOutputStream()
        assertThrows(UpdateException::class.java) { copyBounded(ByteArrayInputStream(ByteArray(20_001)), out, 20_000) }
        assertTrue(out.size() <= 20_000)
    }

    @Test
    fun overflowMessageNamesWhatWasBeingRead() {
        fun message(what: String) = assertThrows(UpdateException::class.java) {
            copyBounded(ByteArrayInputStream(ByteArray(5000)), ByteArrayOutputStream(), 4096, what = what)
        }.message!!
        assertEquals("checksum file is larger than the allowed 4096 bytes", message("checksum file"))
        assertEquals("release information is larger than the allowed 4096 bytes", message("release information"))
    }

    @Test
    fun boundedCopyProgressCanCancel() {
        assertThrows(IllegalStateException::class.java) {
            copyBounded(ByteArrayInputStream(ByteArray(100_000)), ByteArrayOutputStream(), 100_000) { check(false) }
        }
    }

    // endregion
}
