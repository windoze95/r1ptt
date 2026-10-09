package dev.r1ptt.update

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class ReleaseManifestTest {
    // Ephemeral test-only keys: never persisted, used as credentials, or uploaded.
    private val key = rsa
    private val cert = "a".repeat(64)
    private fun payload() = JSONObject().put("schema", 1).put("repository", ReleaseManifest.REPOSITORY)
        .put("packageName", ReleaseManifest.PACKAGE).put("versionCode", 2000).put("versionName", "0.2.0")
        .put("tag", "v0.2.0").put("apkName", "robotOS.apk").put("apkSha256", "b".repeat(64))
        .put("apkSize", 123).put("certificateSha256", cert).put("minSdk", 33).put("commitSha", "c".repeat(40))
    private fun signed(json: JSONObject = payload(), pair: KeyPair = key): ByteArray {
        val bytes = json.toString().toByteArray()
        val signature = Signature.getInstance(if (pair.public.algorithm == "RSA") "SHA256withRSA" else "SHA256withECDSA").run {
            initSign(pair.private); update(bytes); sign()
        }
        return JSONObject().put("payload", Base64.getEncoder().encodeToString(bytes))
            .put("signature", Base64.getEncoder().encodeToString(signature)).toString().toByteArray()
    }
    private fun verify(bytes: ByteArray = signed()) = ReleaseManifest.verify(bytes, key.public, cert)
    private fun rejects(json: JSONObject) { assertThrows(UpdateException::class.java) { verify(signed(json)) } }

    @Test fun verifiesRsaAndEcUsingTheInstalledPublicKey() {
        assertEquals(2000L, verify().versionCode)
        assertEquals("https://github.com/windoze95/robotOS/releases/download/v0.2.0/robotOS.apk", verify().apkUrl)
        val ec = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        assertEquals("0.2.0", ReleaseManifest.verify(signed(pair = ec), ec.public, cert).versionName)
    }
    @Test fun rejectsPayloadTamperingBeforeParsingReleaseFields() {
        val envelope = JSONObject(String(signed()))
        envelope.put("payload", Base64.getEncoder().encodeToString(payload().put("versionCode", 3000).toString().toByteArray()))
        assertThrows(UpdateException::class.java) { verify(envelope.toString().toByteArray()) }
    }
    @Test fun refusesAnotherSignerEvenWhenMetadataClaimsTheInstalledCertificate() {
        val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        assertThrows(UpdateException::class.java) { ReleaseManifest.verify(signed(), other.public, cert) }
        assertThrows(UpdateException::class.java) { ReleaseManifest.verify(signed(), key.public, "d".repeat(64)) }
    }
    @Test fun rejectsMalformedAndOversizedEnvelopes() {
        for (bytes in listOf(byteArrayOf(), "not json".toByteArray(), "{}".toByteArray(),
            "{\"payload\":\"???\",\"signature\":\"???\"}".toByteArray(), ByteArray(ReleaseManifest.MAX_METADATA + 1)))
            assertThrows(UpdateException::class.java) { verify(bytes) }
    }
    @Test fun rejectsWrongReleaseIdentityAndUnknownFields() {
        for ((field, bad) in listOf("schema" to 2, "repository" to "someone/robotOS", "packageName" to "dev.other",
            "apkName" to "../../other.apk", "versionName" to "0.2.1", "certificateSha256" to "d".repeat(64),
            "commitSha" to "main", "extra" to "field")) rejects(payload().put(field, bad))
        rejects(payload().apply { remove("apkSize") })
    }
    @Test fun rejectsCoercedFractionalAndOutOfRangeNumbers() {
        for ((field, bad) in listOf("schema" to "1", "versionCode" to "2000", "versionCode" to 2000.5,
            "apkSize" to 0, "apkSize" to ReleaseManifest.MAX_APK + 1, "minSdk" to 32,
            "minSdk" to Long.MAX_VALUE, "versionCode" to Long.MAX_VALUE)) rejects(payload().put(field, bad))
    }
    @Test fun rejectsMalformedHashAndVersionPaths() {
        for (tag in listOf("v0.2.0/../x", "v0.2.0-beta", "v00.2.0", "v2100.0.0", "v0.0.1"))
            rejects(payload().put("tag", tag))
        rejects(payload().put("apkSha256", "b".repeat(63)))
        rejects(payload().put("apkSha256", "B".repeat(64)))
        rejects(payload().put("versionCode", 2001))
    }
    @Test fun versionCodesAreOrderedAcrossPatchMinorAndMajorBoundaries() {
        assertEquals(2L, ReleaseManifest.releaseVersionCode("v0.0.2"))
        assertEquals(2000L, ReleaseManifest.releaseVersionCode("v0.2.0"))
        assertEquals(2_099_999_999L, ReleaseManifest.releaseVersionCode("v2099.999.999"))
        assertTrue(ReleaseManifest.releaseVersionCode("v1.0.0") > ReleaseManifest.releaseVersionCode("v0.999.999"))
    }
    @Test fun rejectsDowngradeReplayAndNewerAndroidRequirements() {
        val release = verify()
        assertTrue(UpdatePolicy.newer(release, 1, 0, 34))
        assertFalse(UpdatePolicy.newer(release, 2000, 2000, 34))
        assertThrows(UpdateException::class.java) { UpdatePolicy.newer(release, 2001, 0, 34) }
        assertThrows(UpdateException::class.java) { UpdatePolicy.newer(release, 1, 3000, 34) }
        assertThrows(UpdateException::class.java) { UpdatePolicy.newer(release.copy(minSdk = 35), 1, 0, 34) }
    }
    @Test fun apkMustMatchPackageVersionSdkAndSoleCurrentSigner() {
        val r = verify()
        UpdatePolicy.apkMatches(r, "dev.r1ptt", 2000, "0.2.0", listOf(cert), 33)
        val cases = listOf(
            { UpdatePolicy.apkMatches(r, "dev.other", 2000, "0.2.0", listOf(cert), 33) },
            { UpdatePolicy.apkMatches(r, "dev.r1ptt", 1999, "0.2.0", listOf(cert), 33) },
            { UpdatePolicy.apkMatches(r, "dev.r1ptt", 2000, "0.2.1", listOf(cert), 33) },
            { UpdatePolicy.apkMatches(r, "dev.r1ptt", 2000, "0.2.0", listOf("d".repeat(64)), 33) },
            { UpdatePolicy.apkMatches(r, "dev.r1ptt", 2000, "0.2.0", listOf(cert, cert), 33) },
            { UpdatePolicy.apkMatches(r, "dev.r1ptt", 2000, "0.2.0", emptyList(), 33) },
            { UpdatePolicy.apkMatches(r, "dev.r1ptt", 2000, "0.2.0", listOf(cert), 34) },
        )
        cases.forEach { action -> assertThrows(UpdateException::class.java) { action() } }
    }
    companion object { private val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() }
}
