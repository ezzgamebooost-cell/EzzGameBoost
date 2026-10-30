package com.example.gamevpn

import android.content.Context
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.ByteArrayInputStream

/** وایرگارد: فقط ترافیک بازی‌های انتخاب‌شده از تانل رد می‌شود (IncludedApplications). */
object WgTunnel {
    private var backend: GoBackend? = null
    private val tunnel = object : Tunnel {
        override fun getName() = "gamevpn"
        override fun onStateChange(newState: Tunnel.State) {}
    }

    /** باید روی ترد پس‌زمینه صدا زده شود. قبلش مجوز VpnService.prepare باید گرفته شده باشد. */
    fun up(ctx: Context, conf: String, packages: Set<String>) {
        require(packages.isNotEmpty()) { "حداقل یک بازی انتخاب کنید" }
        val b = backend ?: GoBackend(ctx.applicationContext).also { backend = it }
        val cfg = Config.parse(ByteArrayInputStream(withApps(conf, packages).toByteArray()))
        b.setState(tunnel, Tunnel.State.UP, cfg)
    }

    fun down(ctx: Context) {
        val b = backend ?: return
        b.setState(tunnel, Tunnel.State.DOWN, null)
    }

    private fun withApps(conf: String, packages: Set<String>): String {
        val lines = conf.lines().filterNot {
            val k = it.trim().substringBefore('=').trim().lowercase()
            k == "includedapplications" || k == "excludedapplications"
        }.toMutableList()
        val i = lines.indexOfFirst { it.trim().equals("[Interface]", ignoreCase = true) }
        require(i >= 0) { "کانفیگ وایرگارد نامعتبر است" }
        lines.add(i + 1, "IncludedApplications = " + packages.joinToString(", "))
        return lines.joinToString("\n")
    }
}
