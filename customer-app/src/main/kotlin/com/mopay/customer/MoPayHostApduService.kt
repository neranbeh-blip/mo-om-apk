package com.mopay.customer

import android.nfc.cardemulation.HostApduService
import android.os.Bundle

class MoPayHostApduService : HostApduService() {
    companion object {
        private val SELECT_AID = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, 0x06, 0xF0.toByte(), 0x01, 0x02, 0x03, 0x04, 0x05, 0x00)
        private val CARD_RESPONSE = "CARD_DEMO_4821".toByteArray(Charsets.UTF_8) + byteArrayOf(0x90.toByte(), 0x00)
        private val OK = byteArrayOf(0x90.toByte(), 0x00)
    }

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (commandApdu == null) return byteArrayOf(0x6F, 0x00)
        return if (commandApdu.contentEquals(SELECT_AID)) CARD_RESPONSE else OK
    }

    override fun onDeactivated(reason: Int) = Unit
}
