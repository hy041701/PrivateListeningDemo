package com.example.privatelistening.phone

import android.Manifest
import android.content.*
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.os.*
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : ComponentActivity() {
    private var service: PhoneListeningService? = null
    private var bound = false
    private var scanInProgress = false
    private lateinit var scanButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var realignButton: Button
    private lateinit var outputButton: Button
    private lateinit var sessionActions: View
    private lateinit var connectionStatus: TextView
    private lateinit var connectionDetail: TextView
    private lateinit var timelineDiagnostics: TextView
    private lateinit var tvTimelineColumn: TextView
    private lateinit var phoneTimelineColumn: TextView
    private lateinit var timelineDiagnosticsDetails: TextView
    private lateinit var connectionIndicator: View
    private lateinit var headphoneIndicator: View
    private lateinit var headphones: TextView
    private var lastHeadphonePromptVersion = 0
    private var headphoneDialog: android.app.AlertDialog? = null
    private val localNetworkPermission = "android.permission.ACCESS_LOCAL_NETWORK"
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as PhoneListeningService.LocalBinder).service
            service?.observer = { render() }
            render()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            showState(PhonePairing.State.ERROR, "播放服务已停止，请重新扫码。")
        }
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val bluetoothPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) showOutputChooserInternal()
        else Toast.makeText(this, "未授权蓝牙设备访问，仍可使用有线耳机或手机扬声器。", Toast.LENGTH_LONG).show()
    }
    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        scanInProgress = false
        val text = result.contents
        if (text == null) render() else {
            val intent = Intent(this, PhoneListeningService::class.java).putExtra("qr", text)
            try {
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
                showState(PhonePairing.State.CONNECTING, "正在启动收听服务…")
            } catch (error: Exception) { showState(PhonePairing.State.ERROR, "服务启动失败：${error.message}") }
        }
    }
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (missingRequiredPermissions().isEmpty()) launchScanner() else {
            scanInProgress = false
            showState(PhonePairing.State.ERROR, "请允许相机及适用的局域网权限后重试；蓝牙权限可选。")
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        scanButton = findViewById(R.id.scan_tv)
        disconnectButton = findViewById(R.id.disconnect_tv)
        realignButton = findViewById(R.id.realign_tv)
        outputButton = findViewById(R.id.select_audio_output)
        sessionActions = findViewById(R.id.session_actions)
        connectionStatus = findViewById(R.id.connection_status)
        connectionDetail = findViewById(R.id.connection_detail)
        timelineDiagnostics = findViewById(R.id.timeline_diagnostics)
        tvTimelineColumn = findViewById(R.id.tv_timeline_column)
        phoneTimelineColumn = findViewById(R.id.phone_timeline_column)
        timelineDiagnosticsDetails = findViewById(R.id.timeline_diagnostics_details)
        connectionIndicator = findViewById(R.id.connection_indicator)
        headphoneIndicator = findViewById(R.id.headphone_indicator)
        headphones = findViewById(R.id.headphones)
        scanButton.setOnClickListener { requestScan() }
        outputButton.setOnClickListener { showOutputChooser() }
        realignButton.setOnClickListener {
            val current = service ?: return@setOnClickListener
            if (current.participantCount > 1) {
                android.app.AlertDialog.Builder(this)
                    .setTitle("全体音画对齐")
                    .setMessage("将暂停电视和所有成员的声音，准备完成后一起继续。是否开始？")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("开始对齐") { _, _ -> current.requestManualAlignment() }
                    .show()
            } else current.requestManualAlignment()
        }
        disconnectButton.setOnClickListener { service?.endListeningFromPhone() }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    private fun render() {
        val current = service
        showState(current?.state ?: PhonePairing.State.IDLE, current?.detail ?: "正在准备应用…")
        val headphoneText = current?.headphoneDescription ?: "正在检查耳机…"
        headphones.text = headphoneText
        timelineDiagnostics.text = current?.diagnostics
            ?: "连接并开始播放后，这里每秒显示一次电视与手机的完整时间线。"
        tvTimelineColumn.text = current?.tvTimelineColumn ?: "等待电视端采样…"
        phoneTimelineColumn.text = current?.phoneTimelineColumn ?: "等待手机端采样…"
        timelineDiagnosticsDetails.text = current?.diagnosticDetails.orEmpty()
        val headphoneReady = headphoneText.startsWith("当前输出")
        headphoneIndicator.backgroundTintList = ColorStateList.valueOf(
            getColor(if (headphoneReady) R.color.status_ready else R.color.status_working)
        )
        outputButton.isEnabled = current?.hasSession == true
        if (current != null && current.headphonePromptVersion > lastHeadphonePromptVersion) {
            lastHeadphonePromptVersion = current.headphonePromptVersion
            showHeadphonePrompt()
        }
    }

    /** 旧版本兼容入口；新流程始终可回退到扬声器，正常情况下不会再显示此弹窗。 */
    private fun showHeadphonePrompt() {
        headphoneDialog?.dismiss()
        headphoneDialog = android.app.AlertDialog.Builder(this)
            .setTitle("音频输出暂不可用")
            .setMessage("请检查手机音量或音频设备后，再点击“音画对齐”。")
            .setPositiveButton("知道了", null)
            .create()
            .also { dialog ->
                dialog.setOnDismissListener { if (headphoneDialog === dialog) headphoneDialog = null }
                dialog.show()
            }
    }
    private fun requestScan() {
        if (scanInProgress || service?.hasSession == true) return

        scanInProgress = true
        scanButton.isEnabled = false

        val permissions = missingPermissions()

        if (permissions.isEmpty()) {
            launchScanner()
        } else {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun missingPermissions(): List<String> {
        val result = missingRequiredPermissions().toMutableList()

        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            result.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        return result
    }

    private fun missingRequiredPermissions(): List<String> {
        val result = mutableListOf<String>()

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            result.add(Manifest.permission.CAMERA)
        }

        if (Build.VERSION.SDK_INT >= 37 && checkSelfPermission(localNetworkPermission) != PackageManager.PERMISSION_GRANTED) {
            result.add(localNetworkPermission)
        }
        return result
    }

    private fun showOutputChooser() {
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }
        showOutputChooserInternal()
    }

    private fun showOutputChooserInternal() {
        val current = service ?: return
        val choices = current.availableOutputChoices()
        if (choices.isEmpty()) {
            Toast.makeText(this, "当前没有可用的音频输出设备。", Toast.LENGTH_SHORT).show()
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("选择音频输出")
            .setItems(choices.map { it.label }.toTypedArray()) { _, index ->
                if (!current.selectOutputDevice(choices[index].id)) {
                    Toast.makeText(this, "切换失败，请确认设备仍已连接。", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun launchScanner() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            // 提示已放在自定义扫码页中，避免取景框内部出现重复文案。
            setPrompt("")
            setBeepEnabled(false)
            setBarcodeImageEnabled(false)
            setCaptureActivity(PortraitCaptureActivity::class.java)
            setOrientationLocked(true)
        }

        try {
            scanLauncher.launch(options)
        } catch (_: Exception) {
            scanInProgress = false
            showState(PhonePairing.State.ERROR, "无法打开扫码页面，请检查相机是否可用。"
            )
        }
    }

    private fun showState(
        state: PhonePairing.State,
        message: String
    ) {
        connectionStatus.text = when (state) {
            PhonePairing.State.IDLE -> "尚未连接电视"
            PhonePairing.State.CONNECTING -> "正在配对"
            PhonePairing.State.CONNECTED -> "电视已连接 · ${service?.participantCount ?: 1}/3 人"
            PhonePairing.State.ERROR -> "连接未完成"
        }

        connectionDetail.text = friendlyMessage(message)

        val busy = state == PhonePairing.State.CONNECTING || state == PhonePairing.State.CONNECTED

        scanButton.isEnabled = !busy && !scanInProgress
        scanButton.visibility = if (busy) View.GONE else View.VISIBLE
        disconnectButton.isEnabled = busy
        // 尚未连接时只保留扫码入口；配对开始后再显示会话操作。
        sessionActions.visibility = if (busy) View.VISIBLE else View.GONE
        realignButton.isEnabled = state == PhonePairing.State.CONNECTED

        val indicatorColor = when (state) {
            PhonePairing.State.IDLE -> R.color.status_idle
            PhonePairing.State.CONNECTING -> R.color.status_working
            PhonePairing.State.CONNECTED -> R.color.status_ready
            PhonePairing.State.ERROR -> R.color.status_error
        }
        connectionIndicator.backgroundTintList =
            ColorStateList.valueOf(getColor(indicatorColor))

        disconnectButton.text =
            if (state == PhonePairing.State.CONNECTING) {
                "取消连接"
            } else {
                "退出连接"
            }
    }

    /** 主页面只显示用户需要理解的状态，采样率、缓冲毫秒等细节仍保留在服务日志中。 */
    private fun friendlyMessage(message: String): String = when {
        message.startsWith("已收到电视音频") -> message
        message.startsWith("已收到约") -> "正在缓冲声音，即将同步播放"
        message.startsWith("正在准备音频") -> "正在准备电视声音…"
        message.startsWith("手机输出检查通过") -> "音频输出已就绪，正在等待电视同步"
        message.startsWith("音频输出已预填") -> "电视已连接，正在准备声音"
        message.startsWith("已约定起播时间") -> "正在进行音画同步…"
        else -> message
    }

    override fun onStart() {
        super.onStart()
        bound = bindService(Intent(this, PhoneListeningService::class.java), serviceConnection, BIND_AUTO_CREATE)
    }
    override fun onStop() {
        headphoneDialog?.dismiss()
        service?.observer = null
        if (bound) { unbindService(serviceConnection); bound = false }
        service = null
        super.onStop()
    }
}
