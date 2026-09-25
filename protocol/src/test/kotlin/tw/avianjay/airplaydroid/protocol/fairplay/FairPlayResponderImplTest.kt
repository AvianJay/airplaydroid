package tw.avianjay.airplaydroid.protocol.fairplay

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * The assembled responder: m2 challenge + local SAP -> 20-byte response.
 *
 * ### These tests used to pass, and that was the problem
 *
 * They asserted that the responder returned 20 deterministic bytes that depended
 * on both inputs. It did -- but those bytes were Phase 1's bridge output, not the
 * response, so every receiver rejected them. The tests were satisfied by a wrong
 * implementation, because they checked *shape* (20 bytes, input-dependent) rather
 * than *value*.
 *
 * The only test that can catch that is the full-chain corpus
 * ([FairPlayFullChainGoldenTest]) or hardware. These tests now pin the honest
 * current state: the responder reaches the missing Phase 2 and says so.
 */
class FairPlayResponderImplTest {

    /** The captured m2 body from a real receiver. */
    private val challenge = hex(
        "46504c5903010200000000820203" +
            "9001e1727e0f57f9f5880db104a6257a23f5cfff1abbe1e93045251afb97eb9fc0" +
            "011ebe0f3a81df5b691d76acb2f7a5c708e3d328f56bb39dbde5f29c8a17f48148" +
            "7e3ae863c678325422e6f78e166d18aa7fd636258bce28726f661f738893ce4431" +
            "1e4be6c0535193e5ef72e8686233729c227d820c999445d89246c8c359"
    ).copyOfRange(14, 142)

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** The fixed local SAP the published golden corpus was generated with. */
    private val corpusLocalSap = hex(
        "0001e4e3dd688293e6fa66b95ba41768e587c65f750218ff1be21543d573cefb" +
            "087bd36e0c6363c3c8242f4abcfa6d660b801032015405eb4ab04dda7aeff38f" +
            "fb36f4cfa48f0b5d92ae363f68b45925bbe6413ab6bdc4968f548d21e67d20f1" +
            "912b6820e53f1013cde29df7350a9b9fa7c51320aea62d2949786c87642e34ba"
    )

    @Test
    fun `the responder answers the all-zero challenge with the golden response`() {
        // Phase 2 is implemented, so this must now produce the real response
        // rather than throwing. The all-zero challenge under the fixed corpus
        // SAP is the published `zero` vector.
        val response = FairPlayResponderImpl.respondWithSap(
            FairPlayRecords.Mode.MODE_3,
            ByteArray(128),
            corpusLocalSap,
        )
        assertEquals(
            "6f627565f3e77f5b5ede91beee7baf92e4241e0b",
            response.joinToString("") { "%02x".format(it.toInt() and 0xFF) },
        )
    }

    @Test
    fun `a mode other than 3 is refused before reaching Phase 2`() {
        // The mode check must come first: it is a different, cheaper failure, and
        // a caller should learn the mode is unsupported rather than that Phase 2
        // is missing.
        val sap = ByteArray(128).also { it[1] = 0x01 }
        for (mode in listOf(
            FairPlayRecords.Mode.MODE_0,
            FairPlayRecords.Mode.MODE_1,
            FairPlayRecords.Mode.MODE_2,
        )) {
            assertFailsWith<FairPlayResponder.Companion.Unsupported> {
                FairPlayResponderImpl.respondWithSap(mode, challenge, sap)
            }
        }
    }

    @Test
    fun `wrongly sized inputs are refused`() {
        val sap = ByteArray(128).also { it[1] = 0x01 }
        assertFailsWith<IllegalArgumentException> {
            FairPlayResponderImpl.respondWithSap(FairPlayRecords.Mode.MODE_3, ByteArray(127), sap)
        }
        assertFailsWith<IllegalArgumentException> {
            FairPlayResponderImpl.respondWithSap(FairPlayRecords.Mode.MODE_3, challenge, ByteArray(127))
        }
    }

    /**
     * Phase 1 and the bridge are still correct and still worth pinning.
     *
     * They are what Phase 2 will consume, and they are independently verified
     * against upstream's own corpora -- so this records that the *missing* piece
     * is Phase 2 and nothing upstream of it.
     */
    @Test
    fun `phase 1 and the bridge still produce the verified intermediate`() {
        val gp = FairPlayWhiteBox.phase1(challenge)
        assertEquals(128, gp.size)

        val x9 = FairPlaySapCore.bridgeX9HeadForSap(ByteArray(128).also { it[1] = 0x01 }, gp)
        assertEquals(FairPlayPhase2.X9_BYTES, x9.size)

        // Phase 2 must not be an identity: if x9 already equalled the response,
        // this project would work and the whole gap would be imaginary.
        val goldenForAllZeroChallenge = hex("6f627565f3e77f5b5ede91beee7baf92e4241e0b")
        val x9ForAllZero = FairPlaySapCore.bridgeX9HeadForSap(ByteArray(128), FairPlayWhiteBox.phase1(ByteArray(128)))
        assertNotEquals(
            goldenForAllZeroChallenge.joinToString("") { "%02x".format(it) },
            x9ForAllZero.joinToString("") { "%02x".format(it) },
            "Phase 2 is not an identity, so it is genuinely missing",
        )
    }
}
