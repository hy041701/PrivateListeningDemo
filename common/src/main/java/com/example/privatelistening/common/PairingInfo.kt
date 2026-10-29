package com.example.privatelistening.common

import org.json.JSONObject
import java.util.UUID

/**
 * 两端共享的配对信息。
 * 电视调用 toQrText 生成二维码内容；
 * 手机调用 fromQrText 解析扫码结果。
 */
//防止扫描到任意二维码后就发起连接
data class PairingInfo(
    val host: String,
    val port: Int,
    val sessionId: String,
    val token: String,
    val tvName: String
) {

    fun toQrText(): String {
        return JSONObject()
            .put("type", "private_listening")
            .put("version", DemoConfig.PROTOCOL_VERSION)
            .put("host", host)
            .put("port", port)
            .put("sessionId", sessionId)
            .put("token", token)
            .put("tvName", tvName)
            .toString()
    }

    companion object {

        /**
         * 解析并校验扫码结果
         * 校验失败会抛出 IllegalArgumentException，
         * 手机页面负责捕获并显示错误，不继续连接。
         */
        fun fromQrText(text: String): PairingInfo {
            require(text.isNotBlank()) { "二维码内容为空" }
            require(text.length <= 4096) { "二维码内容过长" }

            val json = try {
                JSONObject(text)
            } catch (_: Exception) {
                throw IllegalArgumentException("这不是有效的自在听二维码")
            }

            require(
                json.optString("type") == "private_listening"
            ) { "请扫描电视自在听浮窗中的二维码"
            }

            require(json.optInt("version", -1) == DemoConfig.PROTOCOL_VERSION
            ) { "手机与电视协议版本不一致"
            }

            val host = json.optString("host").trim()
            val port = json.optInt("port", -1)
            val sessionId = json.optString("sessionId")
            val token = json.optString("token")

            // 当前电视端生成 IPv4 地址，手机按相同格式校验。
            require(isValidIpv4(host)) { "二维码中的电视地址无效" }
            require(port in 1..65535) { "二维码中的连接端口无效" }
            require(isValidUuid(sessionId)) { "二维码中的会话编号无效" }
            require(isValidUuid(token)) { "二维码中的配对凭据无效" }

            val tvName = json.optString("tvName", "电视").trim().take(60).ifBlank { "电视" }

            return PairingInfo(
                host = host,
                port = port,
                sessionId = sessionId,
                token = token,
                tvName = tvName
            )
        }

        private fun isValidIpv4(host: String): Boolean {
            val parts = host.split(".")

            if (parts.size != 4) return false

            val numbers = mutableListOf<Int>()

            for (part in parts) {
                if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' }
                ) {
                    return false
                }
                val number = part.toIntOrNull() ?: return false
                if (number !in 0..255) return false
                numbers.add(number)
            }

            // 排除未指定地址、回环地址和组播等地址。
            if (numbers[0] !in 1..223 || numbers[0] == 127
            ) {
                return false
            }

            // 与电视端保持一致，暂不支持链路本地地址。
            if (numbers[0] == 169 && numbers[1] == 254) {
                return false
            }
            return true
        }

        private fun isValidUuid(value: String): Boolean {
            return runCatching {
                UUID.fromString(value)
                    .toString()
                    .equals(value, ignoreCase = true)
            }.getOrDefault(false)
        }
    }
}