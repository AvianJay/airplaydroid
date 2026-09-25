package tw.avianjay.airplaydroid.protocol.mirror

/**
 * What a mirroring session must offer to receive encoded video.
 *
 * [ScreenEncoder] drives a session without caring which protocol is underneath.
 * Before this interface existed the encoder took a concrete `MirrorSession`
 * (AirPlay 2), which is what made the legacy path impossible to add without
 * touching the encoder: the two protocols frame video completely differently --
 * HAP-sealed RTSP frames on one side, 128-byte-header packets on the other -- but
 * the *encoder's* view of them is identical.
 *
 * Both [MirrorSession] and [LegacyVideoStream] satisfy this.
 */
interface VideoStreamSink {

    /**
     * Sends the `avcC` record, and the display size it belongs to.
     *
     * Called at the start of a stream and again whenever the video format
     * changes. A receiver that never sees one has no SPS/PPS and cannot decode
     * anything.
     */
    fun setCodecConfig(avcC: ByteArray, width: Int, height: Int)

    /**
     * Sends one video frame as AVCC-framed H.264.
     *
     * [keyframe] lets a protocol that has a keyframe flag use it; the legacy
     * packet format has none, so it is free to ignore it.
     */
    fun sendFrame(avcc: ByteArray, keyframe: Boolean, captureNanos: Long = System.nanoTime())
}

/**
 * The display a receiver reports, used to size the encoder canvas.
 *
 * Deliberately not `MirrorSession.Display`: the legacy path learns its display
 * from `/stream.xml` and the AirPlay 2 path from an RTSP SETUP reply, and neither
 * should have to depend on the other's type. Callers convert explicitly.
 */
data class ReceiverDisplay(val width: Int, val height: Int)
