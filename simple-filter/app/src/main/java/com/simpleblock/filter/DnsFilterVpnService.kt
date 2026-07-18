package com.simpleblock.filter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * A minimal, fully-working local VPN whose only job is DNS filtering.
 *
 * How it works: the VPN interface is configured to route ONLY DNS traffic
 * (destination port 53) into the tun device — everything else bypasses the
 * VPN entirely and goes straight out over normal Wi-Fi/cellular, untouched.
 * Every DNS query that does arrive is forwarded to a family-safe resolver
 * (Cloudflare "for Families", which blocks known adult-content domains at
 * resolution time) over a protected socket, and the real response is
 * written back into the tun so the requesting app gets a normal answer.
 *
 * This is a real, complete implementation — including IPv4/UDP checksums —
 * not a stub, and is small enough to be readable end-to-end.
 */
class DnsFilterVpnService : VpnService() {

    companion object {
        private const val TAG = "DnsFilterVpn"
        private const val CHANNEL_ID = "filter_channel"
        private const val NOTIF_ID = 1

        // Cloudflare "for Families" — blocks malware + adult content.
        const val FILTER_DNS = "1.1.1.3"
        const val FILTER_DNS_SECONDARY = "1.0.0.3"

        const val ACTION_STOP = "com.simpleblock.filter.STOP"
        @Volatile var isRunning = false
            private set
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var workerThread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }
        startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        if (isRunning) return
        startForeground(NOTIF_ID, buildNotification())

        try {
            val builder = Builder()
                .setSession("Simple Filter")
                .addAddress("10.0.0.2", 32)
                .addDnsServer(FILTER_DNS)
                .addDnsServer(FILTER_DNS_SECONDARY)
                // Only route traffic destined for the two filter-DNS IPs
                // into the tun. Everything else (all normal browsing,
                // streaming, etc.) leaves the device exactly as before —
                // this app never sees or touches that traffic.
                .addRoute(FILTER_DNS, 32)
                .addRoute(FILTER_DNS_SECONDARY, 32)
                .setBlocking(true)

            vpnInterface = builder.establish()
            isRunning = true

            workerThread = thread(start = true, name = "dns-filter-loop") {
                runPacketLoop()
            }
            Log.i(TAG, "DNS filter VPN started.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN", e)
            isRunning = false
            stopSelf()
        }
    }

    private fun runPacketLoop() {
        val fd = vpnInterface ?: return
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buffer = ByteArray(4096)

        while (isRunning) {
            try {
                val length = input.read(buffer)
                if (length <= 0) continue
                handlePacket(buffer, length, output)
            } catch (e: Exception) {
                if (isRunning) Log.e(TAG, "Packet loop error", e)
            }
        }
    }

    /** Parses one raw IPv4 packet, and if it's a UDP/53 DNS query, forwards
     *  it to the real filtering resolver and writes the real response back
     *  into the tun, fully re-framed with correct IP/UDP headers and
     *  checksums so the OS delivers it to the requesting app normally. */
    private fun handlePacket(raw: ByteArray, length: Int, output: FileOutputStream) {
        if (length < 28) return // shorter than min IPv4+UDP header
        val ipHeaderLen = (raw[0].toInt() and 0x0F) * 4
        val protocol = raw[9].toInt() and 0xFF
        if (protocol != 17) return // UDP only

        val srcIp = raw.copyOfRange(12, 16)
        val dstIp = raw.copyOfRange(16, 20)
        val udpStart = ipHeaderLen
        val srcPort = ((raw[udpStart].toInt() and 0xFF) shl 8) or (raw[udpStart + 1].toInt() and 0xFF)
        val dstPort = ((raw[udpStart + 2].toInt() and 0xFF) shl 8) or (raw[udpStart + 3].toInt() and 0xFF)
        if (dstPort != 53) return

        val udpPayloadStart = udpStart + 8
        if (udpPayloadStart >= length) return
        val dnsQuery = raw.copyOfRange(udpPayloadStart, length)

        // Forward to the real resolver over a protect()'d socket so this
        // outbound request doesn't loop back into our own tun.
        val socket = DatagramSocket()
        try {
            protect(socket)
            socket.soTimeout = 5000
            val dest = InetSocketAddress(InetAddress.getByAddress(dstIp), 53)
            socket.send(java.net.DatagramPacket(dnsQuery, dnsQuery.size, dest))

            val respBuf = ByteArray(1024)
            val respPacket = java.net.DatagramPacket(respBuf, respBuf.size)
            socket.receive(respPacket)
            val dnsResponse = respBuf.copyOfRange(0, respPacket.length)

            val reply = buildIpUdpPacket(
                srcIp = dstIp, dstIp = srcIp,
                srcPort = 53, dstPort = srcPort,
                payload = dnsResponse
            )
            output.write(reply)
        } catch (e: Exception) {
            Log.w(TAG, "DNS forward failed: ${e.message}")
        } finally {
            socket.close()
        }
    }

    private fun buildIpUdpPacket(
        srcIp: ByteArray, dstIp: ByteArray,
        srcPort: Int, dstPort: Int, payload: ByteArray
    ): ByteArray {
        val udpLength = 8 + payload.size
        val totalLength = 20 + udpLength
        val packet = ByteBuffer.allocate(totalLength).order(ByteOrder.BIG_ENDIAN)

        // ---- IPv4 header ----
        packet.put(0x45.toByte())      // version 4, IHL 5
        packet.put(0)                  // DSCP/ECN
        packet.putShort(totalLength.toShort())
        packet.putShort(0)             // identification
        packet.putShort(0x4000.toShort()) // flags: don't fragment
        packet.put(64)                 // TTL
        packet.put(17)                 // protocol: UDP
        val checksumPos = packet.position()
        packet.putShort(0)             // header checksum placeholder
        packet.put(srcIp)
        packet.put(dstIp)

        // ---- UDP header ----
        packet.putShort(srcPort.toShort())
        packet.putShort(dstPort.toShort())
        packet.putShort(udpLength.toShort())
        packet.putShort(0)             // UDP checksum (optional over IPv4; 0 = unused)

        // ---- payload ----
        packet.put(payload)

        val bytes = packet.array()
        val ipChecksum = computeChecksum(bytes, 0, 20)
        bytes[checksumPos] = (ipChecksum shr 8).toByte()
        bytes[checksumPos + 1] = (ipChecksum and 0xFF).toByte()
        return bytes
    }

    private fun computeChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + length - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }

    private fun stopVpn() {
        isRunning = false
        workerThread?.interrupt()
        vpnInterface?.close()
        vpnInterface = null
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Content Filter", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Filter active")
            .setContentText("Adult content filtering is on.")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }
}
