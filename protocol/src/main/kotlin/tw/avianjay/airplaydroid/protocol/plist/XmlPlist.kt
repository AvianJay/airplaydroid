package tw.avianjay.airplaydroid.protocol.plist

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.util.Base64
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reader for Apple's XML property lists.
 *
 * This is not optional. AirPlay is inconsistent about which plist flavour it
 * uses **per endpoint**: `POST /play` takes a binary plist, but
 * `GET /playback-info` is answered with `Content-Type: text/x-apple-plist+xml`.
 * A sender that only speaks binary silently gets nothing back from
 * /playback-info -- no position, no duration, and no rate, which makes pause
 * impossible to implement correctly.
 */
object XmlPlist {

    class FormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

    fun decode(bytes: ByteArray): PlistValue {
        val document = try {
            newSecureFactory().newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        } catch (t: Throwable) {
            throw FormatException("could not parse XML plist", t)
        }

        val root = document.documentElement
            ?: throw FormatException("XML plist has no root element")

        val payload = if (root.tagName == "plist") {
            firstElementChild(root) ?: throw FormatException("empty <plist>")
        } else {
            root
        }

        return parse(payload, 0)
    }

    /**
     * Property lists carry a DOCTYPE pointing at apple.com. Entity resolution and
     * external DTD loading are disabled so parsing a receiver's response can
     * never turn into a network fetch or an XXE read of local files.
     */
    private fun newSecureFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            // false is already the default. Android's factory does not override
            // setXIncludeAware, and the base method throws for any value -- which
            // made every XML plist fail to parse on a phone while passing on the JVM.
            runCatching { isXIncludeAware = false }
            isExpandEntityReferences = false
            setFeatureQuietly("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setFeatureQuietly("http://xml.org/sax/features/external-general-entities", false)
            setFeatureQuietly("http://xml.org/sax/features/external-parameter-entities", false)
        }

    private fun DocumentBuilderFactory.setFeatureQuietly(name: String, value: Boolean) {
        runCatching { setFeature(name, value) }
    }

    private fun parse(element: Element, depth: Int): PlistValue {
        if (depth > 32) throw FormatException("XML plist nested too deeply")

        return when (element.tagName) {
            "dict" -> {
                val entries = LinkedHashMap<String, PlistValue>()
                var node = element.firstChild
                var pendingKey: String? = null
                while (node != null) {
                    if (node.nodeType == Node.ELEMENT_NODE) {
                        val child = node as Element
                        if (child.tagName == "key") {
                            pendingKey = child.textContent.orEmpty()
                        } else {
                            val key = pendingKey
                                ?: throw FormatException("<dict> value without a preceding <key>")
                            entries[key] = parse(child, depth + 1)
                            pendingKey = null
                        }
                    }
                    node = node.nextSibling
                }
                PlistValue.PDict(entries)
            }

            "array" -> {
                val values = mutableListOf<PlistValue>()
                var node = element.firstChild
                while (node != null) {
                    if (node.nodeType == Node.ELEMENT_NODE) {
                        values += parse(node as Element, depth + 1)
                    }
                    node = node.nextSibling
                }
                PlistValue.PArray(values)
            }

            "string" -> PlistValue.PString(element.textContent.orEmpty())
            "real" -> PlistValue.PReal(
                element.textContent?.trim()?.toDoubleOrNull()
                    ?: throw FormatException("bad <real>: " + element.textContent)
            )
            "integer" -> PlistValue.PInt(
                element.textContent?.trim()?.toLongOrNull()
                    ?: throw FormatException("bad <integer>: " + element.textContent)
            )
            "true" -> PlistValue.PBool(true)
            "false" -> PlistValue.PBool(false)
            "data" -> PlistValue.PData(
                runCatching {
                    Base64.getMimeDecoder().decode(element.textContent.orEmpty().trim())
                }.getOrElse { throw FormatException("bad <data>", it) }
            )
            // Dates are not used by any endpoint we drive; keep the raw text
            // rather than failing the whole document.
            "date" -> PlistValue.PString(element.textContent.orEmpty())

            else -> throw FormatException("unsupported plist element <" + element.tagName + ">")
        }
    }

    private fun firstElementChild(element: Element): Element? {
        var node = element.firstChild
        while (node != null) {
            if (node.nodeType == Node.ELEMENT_NODE) return node as Element
            node = node.nextSibling
        }
        return null
    }
}

/**
 * Entry point for anything that comes off the wire: AirPlay picks the plist
 * flavour per endpoint, so callers must not assume one or the other.
 */
object Plists {

    private val BPLIST_MAGIC = "bplist00".toByteArray(Charsets.US_ASCII)

    fun looksBinary(bytes: ByteArray): Boolean =
        bytes.size >= BPLIST_MAGIC.size &&
            bytes.copyOfRange(0, BPLIST_MAGIC.size).contentEquals(BPLIST_MAGIC)

    /** Decodes either flavour, sniffing on the `bplist00` magic. */
    fun decode(bytes: ByteArray): PlistValue =
        if (looksBinary(bytes)) BinaryPlist.decode(bytes) else XmlPlist.decode(bytes)
}
