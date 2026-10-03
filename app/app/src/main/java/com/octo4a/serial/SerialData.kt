package com.octo4a.serial

import com.octo4a.utils.isBitSet

class SerialData(val data: ByteArray, val baudrate: Int, val c_iflag: Int, val c_oflag: Int, val c_cflag: Int, val c_lflag: Int) {
    companion object {
        const val TIOCPKT_DATA = 0
        const val TIOCPKT_FLUSHREAD = 1
        const val TIOCPKT_IOCTL = 64
    }

    val isStartPacket = data[0].toInt() == TIOCPKT_FLUSHREAD || data[0].toInt() == TIOCPKT_IOCTL
    val serialData: ByteArray
        get() = data.copyOfRange(1, data.size)
}