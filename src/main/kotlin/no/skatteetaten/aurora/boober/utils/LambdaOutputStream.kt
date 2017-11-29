package no.skatteetaten.aurora.boober.utils

import java.io.OutputStream

class LambdaOutputStream(val fn: (String) -> Unit) : OutputStream() {

    var mem: String = ""

    override fun write(b: Int) {
        val bytes = ByteArray(1)
        bytes[0] = (b and 0xff).toByte()
        mem += String(bytes)

        if (mem.endsWith("\n")) {
            mem = mem.substring(0, mem.length - 1)
            flush()
        }
    }

    override fun flush() {
        fn.invoke(mem)
        mem = ""
    }

}