package com.example.privatelistening.phone

import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView

/** 使用项目自己的竖屏视觉外壳；相机生命周期和二维码识别仍交给 JourneyApps。 */
class PortraitCaptureActivity : CaptureActivity() {
    override fun initializeContent(): DecoratedBarcodeView {
        setContentView(R.layout.activity_scanner)
        findViewById<android.view.View>(R.id.scanner_close).setOnClickListener { finish() }
        return findViewById<DecoratedBarcodeView>(R.id.zxing_barcode_scanner).also {
            // 背景提供圆角轮廓，裁掉内部相机预览的直角边缘。
            it.clipToOutline = true
        }
    }
}
