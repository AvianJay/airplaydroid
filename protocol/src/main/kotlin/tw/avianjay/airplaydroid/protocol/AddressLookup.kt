package tw.avianjay.airplaydroid.protocol

import tw.avianjay.airplaydroid.protocol.http.AirPlayRequest
import tw.avianjay.airplaydroid.protocol.http.SocketAirPlayConnection
import tw.avianjay.airplaydroid.protocol.plist.BinaryPlist
import tw.avianjay.airplaydroid.protocol.plist.PlistValue
import tw.avianjay.airplaydroid.protocol.plist.Plists
import java.io.IOException

/**
 * Builds an [AirPlayDevice] for a receiver that mDNS cannot reach -- an address
 * across a VPN or a phone tunnel, or the host seen from an Android emulator
 * (`10.0.2.2`), where multicast does not pass.
 *
 * `GET /info` answers with what the TXT record would have carried. Two shapes:
 *
 *  - asked with `{"qualifier": ["txtAirPlay"]}`, an Apple TV returns its
 *    `_airplay._tcp` TXT record verbatim under `txtAirPlay`, which parses
 *    exactly like a discovered one;
 *  - receivers that ignore the qualifier (LonelyScreen, RPiPlay and the
 *    dongles built on it) return a plain dictionary -- `features` as a 64-bit
 *    integer, `statusFlags`, `pk` as data -- which is mapped onto the same keys.
 */
object AddressLookup {

    class Failure(message: String, cause: Throwable? = null) : IOException(message, cause)

    const val DEFAULT_PORT = 7000

    fun lookup(host: String, port: Int = DEFAULT_PORT): AirPlayDevice {
        val response = try {
            SocketAirPlayConnection(Endpoint(host, port), readTimeoutMs = 5_000).use { connection ->
                connection.exchange(
                    AirPlayRequest(
                        method = "GET",
                        uri = "/info",
                        protocol = AirPlayRequest.RTSP_1_0,
                        headers = listOf(
                            "CSeq" to "1",
                            "User-Agent" to "AirPlay/220.68",
                            "Content-Type" to "application/x-apple-binary-plist",
                        ),
                        body = BinaryPlist.encode(
                            PlistValue.dict("qualifier" to PlistValue.PArray(listOf(PlistValue.PString("txtAirPlay"))))
                        ),
                    )
                )
            }
        } catch (e: IOException) {
            throw Failure("no AirPlay receiver answered at $host:$port (${e.message})", e)
        }
        if (!response.isSuccess) throw Failure("$host:$port answered /info with ${response.status}")
        val info = runCatching { Plists.decode(response.body) }.getOrNull() as? PlistValue.PDict
            ?: throw Failure("$host:$port did not answer /info with a dictionary")
        return fromInfo(host, port, info)
    }

    /** The device an `/info` dictionary describes. */
    fun fromInfo(host: String, port: Int, info: PlistValue.PDict): AirPlayDevice {
        val attributes = (info["txtAirPlay"] as? PlistValue.PData)?.value?.let(::parseTxt)
            ?: mapInfo(info)
        val txt = AirPlayTxt.parse(attributes)
        val key = TxtRecords.normalizeDeviceKey(txt.deviceId) ?: "$host:$port"
        val name = info.string("name")?.takeIf { it.isNotBlank() } ?: "$host:$port"
        return AirPlayDevice(key = key, displayName = name, airPlayEndpoint = Endpoint(host, port), airPlayTxt = txt)
    }

    /** A DNS TXT record: length-prefixed `key=value` strings. */
    internal fun parseTxt(bytes: ByteArray): Map<String, ByteArray?> {
        val out = linkedMapOf<String, ByteArray?>()
        var i = 0
        while (i < bytes.size) {
            val length = bytes[i].toInt() and 0xFF
            val end = minOf(bytes.size, i + 1 + length)
            val entry = bytes.copyOfRange(i + 1, end)
            val eq = entry.indexOf('='.code.toByte())
            if (eq < 0) out[String(entry)] = null
            else out[String(entry, 0, eq)] = entry.copyOfRange(eq + 1, entry.size)
            i = end
        }
        return out
    }

    private fun mapInfo(info: PlistValue.PDict): Map<String, ByteArray?> {
        val out = linkedMapOf<String, ByteArray?>()
        fun put(key: String, value: String?) {
            if (value != null) out[key] = value.toByteArray()
        }
        put("deviceid", info.string("deviceID") ?: info.string("macAddress"))
        put("model", info.string("model"))
        put("srcvers", info.string("sourceVersion"))
        put("pi", info.string("pi"))
        (info["features"] as? PlistValue.PInt)?.value?.let { features ->
            val low = features and 0xFFFF_FFFFL
            val high = features ushr 32
            put("features", if (high == 0L) "0x%X".format(low) else "0x%X,0x%X".format(low, high))
        }
        (info["statusFlags"] as? PlistValue.PInt)?.value?.let { put("flags", "0x%X".format(it)) }
        (info["pk"] as? PlistValue.PData)?.value?.let { pk -> put("pk", pk.joinToString("") { "%02x".format(it) }) }
        return out
    }
}
