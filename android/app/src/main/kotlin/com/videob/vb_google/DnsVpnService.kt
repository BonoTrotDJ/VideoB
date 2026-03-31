package com.videob.vb_google

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class DnsVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null
    private var workerThread: Thread? = null
    private val running = AtomicBoolean(false)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                stopSelf()
            }

            else -> {
                if (!running.get()) {
                    startVpn()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopVpn()
        stopSelf()
        super.onRevoke()
    }

    private fun startVpn() {
        val builder = Builder()
            .setSession("VideoB DNS")
            .addAddress(VPN_ADDRESS, 32)
            .addDnsServer(PRIMARY_DNS)
            .addDnsServer(SECONDARY_DNS)
            .addRoute(PRIMARY_DNS, 32)
            .addRoute(SECONDARY_DNS, 32)

        vpnInterface = builder.establish() ?: return
        running.set(true)

        workerThread = thread(name = "VideoBDnsVpn", start = true) {
            runDnsLoop(vpnInterface!!)
        }
    }

    private fun stopVpn() {
        running.set(false)
        workerThread?.interrupt()
        workerThread = null
        vpnInterface?.close()
        vpnInterface = null
    }

    private fun runDnsLoop(vpnFd: ParcelFileDescriptor) {
        FileInputStream(vpnFd.fileDescriptor).use { input ->
            FileOutputStream(vpnFd.fileDescriptor).use { output ->
                val packetBuffer = ByteArray(MAX_PACKET_SIZE)

                while (running.get()) {
                    val length = input.read(packetBuffer)
                    if (length <= 0) {
                        continue
                    }

                    val responsePacket = handlePacket(packetBuffer, length) ?: continue
                    output.write(responsePacket)
                }
            }
        }
    }

    private fun handlePacket(packet: ByteArray, length: Int): ByteArray? {
        if (length < 28) {
            return null
        }

        val version = (packet[0].toInt() ushr 4) and 0x0F
        if (version != 4) {
            return null
        }

        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (ihl < 20 || length < ihl + 8) {
            return null
        }

        val protocol = packet[9].toInt() and 0xFF
        if (protocol != 17) {
            return null
        }

        val sourceIp = packet.copyOfRange(12, 16)
        val destIp = packet.copyOfRange(16, 20)
        val destAddress = InetAddress.getByAddress(destIp).hostAddress ?: return null
        if (destAddress != PRIMARY_DNS && destAddress != SECONDARY_DNS) {
            return null
        }

        val srcPort = readUnsignedShort(packet, ihl)
        val dstPort = readUnsignedShort(packet, ihl + 2)
        if (dstPort != 53) {
            return null
        }

        val udpLength = readUnsignedShort(packet, ihl + 4)
        if (udpLength < 8 || ihl + udpLength > length) {
            return null
        }

        val dnsPayload = packet.copyOfRange(ihl + 8, ihl + udpLength)
        val dnsResponse = forwardDns(destAddress, dnsPayload) ?: return null

        return buildIpv4UdpResponse(
            srcIp = destIp,
            dstIp = sourceIp,
            srcPort = 53,
            dstPort = srcPort,
            requestIpHeader = packet,
            requestIpHeaderLength = ihl,
            payload = dnsResponse,
        )
    }

    private fun forwardDns(serverIp: String, payload: ByteArray): ByteArray? {
        val socket = DatagramSocket().apply {
            soTimeout = 5000
        }
        return try {
            protect(socket)
            val requestPacket = DatagramPacket(
                payload,
                payload.size,
                InetAddress.getByName(serverIp),
                53,
            )
            socket.send(requestPacket)

            val responseBuffer = ByteArray(MAX_PACKET_SIZE)
            val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
            socket.receive(responsePacket)
            responseBuffer.copyOf(responsePacket.length)
        } catch (_: Exception) {
            null
        } finally {
            socket.close()
        }
    }

    private fun buildIpv4UdpResponse(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        requestIpHeader: ByteArray,
        requestIpHeaderLength: Int,
        payload: ByteArray,
    ): ByteArray {
        val udpLength = 8 + payload.size
        val totalLength = 20 + udpLength
        val packet = ByteArray(totalLength)

        packet[0] = 0x45
        packet[1] = 0
        writeUnsignedShort(packet, 2, totalLength)
        packet[4] = requestIpHeader[4]
        packet[5] = requestIpHeader[5]
        packet[6] = 0
        packet[7] = 0
        packet[8] = 64
        packet[9] = 17
        packet[10] = 0
        packet[11] = 0
        System.arraycopy(srcIp, 0, packet, 12, 4)
        System.arraycopy(dstIp, 0, packet, 16, 4)

        writeUnsignedShort(packet, 20, srcPort)
        writeUnsignedShort(packet, 22, dstPort)
        writeUnsignedShort(packet, 24, udpLength)
        writeUnsignedShort(packet, 26, 0)
        System.arraycopy(payload, 0, packet, 28, payload.size)

        writeUnsignedShort(packet, 10, ipv4HeaderChecksum(packet, 20))
        writeUnsignedShort(
            packet,
            26,
            udpChecksum(
                srcIp = srcIp,
                dstIp = dstIp,
                udpSegment = packet.copyOfRange(20, totalLength),
            ),
        )

        return packet
    }

    private fun ipv4HeaderChecksum(packet: ByteArray, headerLength: Int): Int {
        var sum = 0L
        var index = 0
        while (index < headerLength) {
            if (index == 10) {
                index += 2
                continue
            }
            sum += readUnsignedShort(packet, index).toLong()
            index += 2
        }
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return sum.inv().toInt() and 0xFFFF
    }

    private fun udpChecksum(srcIp: ByteArray, dstIp: ByteArray, udpSegment: ByteArray): Int {
        val pseudoLength = 12 + udpSegment.size + (udpSegment.size % 2)
        val buffer = ByteBuffer.allocate(pseudoLength)
        buffer.put(srcIp)
        buffer.put(dstIp)
        buffer.put(0)
        buffer.put(17)
        buffer.putShort(udpSegment.size.toShort())
        buffer.put(udpSegment)
        if (udpSegment.size % 2 != 0) {
            buffer.put(0)
        }

        val bytes = buffer.array()
        var sum = 0L
        var index = 0
        while (index < bytes.size) {
            sum += (((bytes[index].toInt() and 0xFF) shl 8) or
                (bytes[index + 1].toInt() and 0xFF)).toLong()
            index += 2
        }
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        val checksum = sum.inv().toInt() and 0xFFFF
        return if (checksum == 0) 0xFFFF else checksum
    }

    private fun readUnsignedShort(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    private fun writeUnsignedShort(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value ushr 8) and 0xFF).toByte()
        data[offset + 1] = (value and 0xFF).toByte()
    }

    companion object {
        private const val ACTION_STOP = "com.videob.vb_google.STOP_DNS_VPN"
        private const val PRIMARY_DNS = "1.1.1.1"
        private const val SECONDARY_DNS = "1.0.0.1"
        private const val VPN_ADDRESS = "10.10.10.1"
        private const val MAX_PACKET_SIZE = 32767

        fun start(context: Context) {
            context.startService(Intent(context, DnsVpnService::class.java))
        }

        fun stop(context: Context) {
            val intent = Intent(context, DnsVpnService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
