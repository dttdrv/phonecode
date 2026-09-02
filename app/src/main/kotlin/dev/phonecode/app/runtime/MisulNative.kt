package dev.phonecode.app.runtime

internal object MisulNative {
    init {
        System.loadLibrary("phonecode_vm")
    }

    fun abiVersion(): Int = nativeAbiVersion()

    fun open(config: ByteArray): Session = Session(nativeOpen(config))

    private external fun nativeAbiVersion(): Int

    private external fun nativeOpen(config: ByteArray): Long

    private external fun nativeRequest(handle: Long, record: ByteArray): ByteArray

    private external fun nativeNextEvent(handle: Long, timeoutMillis: Int): ByteArray?

    private external fun nativeHostResponse(handle: Long, record: ByteArray)

    private external fun nativeClose(handle: Long)

    class Session internal constructor(private var handle: Long) : AutoCloseable {
        private val ownerThread = Thread.currentThread()

        fun request(record: ByteArray): ByteArray {
            checkOwner()
            check(handle != 0L) { "Misul native session is closed" }
            return nativeRequest(handle, record)
        }

        fun nextEvent(timeoutMillis: Int): ByteArray? {
            checkOwner()
            check(handle != 0L) { "Misul native session is closed" }
            require(timeoutMillis in 0..MAX_EVENT_WAIT_MILLIS) { "Misul event wait must be between 0 and $MAX_EVENT_WAIT_MILLIS ms" }
            return nativeNextEvent(handle, timeoutMillis)
        }

        fun hostResponse(record: ByteArray) {
            checkOwner()
            check(handle != 0L) { "Misul native session is closed" }
            nativeHostResponse(handle, record)
        }

        override fun close() {
            checkOwner()
            if (handle == 0L) return
            nativeClose(handle)
            handle = 0
        }

        private fun checkOwner() {
            check(Thread.currentThread() === ownerThread) {
                "Misul native session must stay on its owning runtime thread"
            }
        }
    }

    private const val MAX_EVENT_WAIT_MILLIS = 60_000
}
