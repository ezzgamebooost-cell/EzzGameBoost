package com.example.gamevpn

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor

/**
 * سرویس VPN برای V2Ray.
 * اینجا TUN ساخته می‌شود و فقط بازی‌های انتخاب‌شده (addAllowedApplication) داخلش می‌آیند.
 *
 * ⚠️ هسته‌ی Xray/sing-box در این پروژه گنجانده نشده (نیاز به AAR دارد).
 * محل اتصال هسته: V2RayCore.start / V2RayCore.stop در پایین همین فایل.
 * راهنما در README.md بخش «اتصال هسته V2Ray».
 */
class V2RayVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopAll(); return START_NOT_STICKY }
        val conf = intent?.getStringExtra(EXTRA_CONFIG) ?: return START_NOT_STICKY
        val pkgs = intent.getStringArrayListExtra(EXTRA_PACKAGES).orEmpty()
        if (pkgs.isEmpty()) { stopAll(); return START_NOT_STICKY }

        val b = Builder()
            .setSession("Game VPN")
            .setMtu(1500)
            .addAddress("172.19.0.1", 30)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
        pkgs.forEach { try { b.addAllowedApplication(it) } catch (_: Exception) {} }
        tun = b.establish()
        val fd = tun ?: run { stopAll(); return START_NOT_STICKY }
        try {
            V2RayCore.start(this, conf, fd)
        } catch (e: Exception) {
            lastError = e.message
            stopAll(); return START_NOT_STICKY
        }
        running = true
        return START_STICKY
    }

    private fun stopAll() {
        V2RayCore.stop()
        tun?.close(); tun = null
        running = false
        stopSelf()
    }

    override fun onDestroy() { stopAll(); super.onDestroy() }
    override fun onRevoke() { stopAll() }

    companion object {
        const val ACTION_STOP = "com.example.gamevpn.STOP"
        const val EXTRA_CONFIG = "config"
        const val EXTRA_PACKAGES = "packages"
        @Volatile var running = false
        @Volatile var lastError: String? = null
    }
}

/** نقطه اتصال هسته. فعلا خالی است؛ بعد از افزودن AAR اینجا را کامل کنید. */
object V2RayCore {
    fun start(service: VpnService, config: String, tun: ParcelFileDescriptor) {
        // TODO: config (لینک vless:// ... یا JSON) را به JSON هسته تبدیل و هسته را با tun.fd اجرا کنید.
        throw UnsupportedOperationException("هسته V2Ray هنوز متصل نشده — README را ببینید")
    }
    fun stop() { /* TODO */ }
}
