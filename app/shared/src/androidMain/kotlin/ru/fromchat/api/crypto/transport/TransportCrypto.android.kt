package ru.fromchat.api.crypto.transport

import com.iwebpp.crypto.TweetNaclFast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.fromchat.api.crypto.transport.TransportCiphertext
import java.security.SecureRandom
import java.util.Base64

actual object TransportCrypto {
    private val random = SecureRandom()

    actual suspend fun encryptWithTransportKey(
        plaintext: String,
        transportPublicKeyB64: String
    ): TransportCiphertext = withContext(Dispatchers.Default) {
        val (cipher, secret) = encryptWithTransportKeyWithEphemeralSecretInner(plaintext, transportPublicKeyB64)
        secret.fill(0)
        cipher
    }

    actual suspend fun encryptWithTransportKeyWithEphemeralSecret(
        plaintext: String,
        transportPublicKeyB64: String
    ): Pair<TransportCiphertext, ByteArray> = withContext(Dispatchers.Default) {
        encryptWithTransportKeyWithEphemeralSecretInner(plaintext, transportPublicKeyB64)
    }

    private fun encryptWithTransportKeyWithEphemeralSecretInner(
        plaintext: String,
        transportPublicKeyB64: String
    ): Pair<TransportCiphertext, ByteArray> {
        val transportPublicKey = Base64.getDecoder().decode(transportPublicKeyB64)
        val keyPair = TweetNaclFast.Box.keyPair()
        val box = TweetNaclFast.Box(transportPublicKey, keyPair.secretKey)
        val nonce = ByteArray(TweetNaclFast.Box.nonceLength)
        random.nextBytes(nonce)
        val ciphertext = box.box(plaintext.encodeToByteArray(), nonce)
        val encoder = Base64.getEncoder()
        val cipher = TransportCiphertext(
            clientPublicKeyB64 = encoder.encodeToString(keyPair.publicKey),
            nonceB64 = encoder.encodeToString(nonce),
            ciphertextB64 = encoder.encodeToString(ciphertext)
        )
        return cipher to keyPair.secretKey.copyOf()
    }

    actual suspend fun encryptFileForTransport(
        fileBytes: ByteArray,
        transportPublicKeyB64: String,
        ephemeralSecretKey: ByteArray
    ): ByteArray = withContext(Dispatchers.Default) {
        val transportPublicKey = Base64.getDecoder().decode(transportPublicKeyB64)
        val box = TweetNaclFast.Box(transportPublicKey, ephemeralSecretKey)
        val nonce = ByteArray(TweetNaclFast.Box.nonceLength)
        random.nextBytes(nonce)
        val ciphertext = box.box(fileBytes, nonce)
        val result = ByteArray(nonce.size + ciphertext.size)
        System.arraycopy(nonce, 0, result, 0, nonce.size)
        System.arraycopy(ciphertext, 0, result, nonce.size, ciphertext.size)
        result
    }
}
