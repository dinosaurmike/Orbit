package com.example.companion2

import java.security.interfaces.ECPublicKey
import javax.crypto.AEADBadTagException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DeskCryptoTest {
    private fun unhex(text: String) = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun hkdfMatchesTheStandard() {
        // RFC 5869, test case 1 (SHA-256)
        val okm = DeskCrypto.hkdf(
            unhex("0b".repeat(22)), unhex("000102030405060708090a0b0c"), unhex("f0f1f2f3f4f5f6f7f8f9"), 42
        )
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",  // gitleaks:allow (a published RFC 5869 test vector, not a secret)
            DeskCrypto.hex(okm)
        )
        // RFC 5869, test case 3: no salt (32 zero bytes), no info
        assertEquals(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",  // gitleaks:allow (a published RFC 5869 test vector, not a secret)
            DeskCrypto.hex(DeskCrypto.hkdf(unhex("0b".repeat(22)), null, ByteArray(0), 42))
        )
    }

    @Test
    fun keySwapGivesBothSidesTheSameSecret() {
        val phone = DeskCrypto.newKeyPair()
        val desk = DeskCrypto.newKeyPair()
        val phoneRaw = DeskCrypto.rawPublicKey(phone.public as ECPublicKey)
        val deskRaw = DeskCrypto.rawPublicKey(desk.public as ECPublicKey)
        assertEquals(65, phoneRaw.size)
        assertEquals(4.toByte(), phoneRaw[0])
        // The raw point turns back into the same key
        val back = DeskCrypto.publicKeyFromRaw(phoneRaw, (phone.public as ECPublicKey).params) as ECPublicKey
        assertEquals((phone.public as ECPublicKey).w, back.w)
        val a = DeskCrypto.sharedSecret(phone, deskRaw)
        assertArrayEquals(a, DeskCrypto.sharedSecret(desk, phoneRaw))
        assertEquals(32, a.size)
    }

    @Test
    fun aPointOffTheCurveIsRefused() {
        val phone = DeskCrypto.newKeyPair()
        val bad = byteArrayOf(4) + ByteArray(63) + byteArrayOf(1)
        assertThrows(IllegalArgumentException::class.java) { DeskCrypto.sharedSecret(phone, bad) }
        assertThrows(IllegalArgumentException::class.java) { DeskCrypto.sharedSecret(phone, ByteArray(33)) }
    }

    @Test
    fun transcriptPutsTheLengthBeforeEachPart() {
        assertEquals(
            "00000002" + "abcd" + "00000000" + "00000001" + "01",
            DeskCrypto.hex(DeskCrypto.transcript(unhex("abcd"), ByteArray(0), byteArrayOf(1)))
        )
        // Moving a byte from one part to the next changes it
        val one = DeskCrypto.transcript(unhex("abcd"), unhex("ef"))
        val two = DeskCrypto.transcript(unhex("ab"), unhex("cdef"))
        assertEquals(false, one.contentEquals(two))
    }

    @Test
    fun answersOnlyOpenWithTheRightKeyForTheRightRequest() {
        val key = DeskCrypto.random(32)
        val sealed = DeskCrypto.seal(key, "a screen picture".toByteArray(), "sig-1".toByteArray())
        assertEquals("a screen picture", String(DeskCrypto.open(key, sealed, "sig-1".toByteArray())))
        assertThrows(AEADBadTagException::class.java) { DeskCrypto.open(key, sealed, "sig-2".toByteArray()) }
        assertThrows(AEADBadTagException::class.java) {
            DeskCrypto.open(DeskCrypto.random(32), sealed, "sig-1".toByteArray())
        }
        val changed = sealed.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() }
        assertThrows(AEADBadTagException::class.java) { DeskCrypto.open(key, changed, "sig-1".toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { DeskCrypto.open(key, ByteArray(10), ByteArray(0)) }
    }
}
