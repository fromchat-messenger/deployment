package ru.fromchat.crypto.transport

import com.ionspin.kotlin.crypto.LibsodiumInitializer
import com.ionspin.kotlin.crypto.box.Box
import com.ionspin.kotlin.crypto.box.crypto_box_NONCEBYTES
import com.ionspin.kotlin.crypto.util.LibsodiumRandom
import com.pr0gramm3r101.utils.crypto.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual object TransportCrypto {
    actual suspend fun encryptWithTransportKey(
        plaintext: String,
        transportPublicKeyB64: String
    ) = withContext(Dispatchers.Default) {
        if (!LibsodiumInitializer.isInitialized()) {
            LibsodiumInitializer.initialize()
        }

        val keyPair = Box.keypair()
        val nonce = LibsodiumRandom.buf(crypto_box_NONCEBYTES)
        val ciphertext = Box.easy(
            plaintext.encodeToByteArray().toUByteArray(),
            nonce,
            Base64.decode(transportPublicKeyB64).toUByteArray(),
            keyPair.secretKey
        )

        TransportCiphertext(
            clientPublicKeyB64 = Base64.encode(keyPair.publicKey.toByteArray()),
            nonceB64 = Base64.encode(nonce.toByteArray()),
            ciphertextB64 = Base64.encode(ciphertext.toByteArray())
        )
    }
}
