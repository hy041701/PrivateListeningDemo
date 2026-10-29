package com.example.privatelistening.common

object DemoConfig {
    // 多人会话包含成员状态和独立校时，拒绝与旧单人APK混装。
    // v6：音频帧增加电视写出单调时钟，用于端到端链路诊断；两端必须同时升级。
    const val PROTOCOL_VERSION = 6
    const val CONTROL_PORT = 8766
    const val AUDIO_PORT = 8767
    const val SAMPLE_RATE = 48_000

    /**
     *TCP是当前默认值；原始UDP代码保留，
     */
    enum class AudioTransport(val wireName: String) {
        UDP("UDP_PCM"),
        TCP("TCP_PCM")
    }

    @JvmField
    val AUDIO_TRANSPORT = AudioTransport.TCP

    const val TCP_CONNECT_TIMEOUT_MS = 3_000
    const val TCP_READ_TIMEOUT_MS = 4_000
}

