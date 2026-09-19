package com.darusc.vcamdroid

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Size
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.darusc.vcamdroid.databinding.ActivityMainBinding
import com.darusc.vcamdroid.networking.ConnectionManager
import com.darusc.vcamdroid.util.Logger
import com.darusc.vcamdroid.util.applySystemBarInsets
import com.darusc.vcamdroid.video.Camera
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : AppCompatActivity(), ConnectionManager.ConnectionStateCallback {

    private lateinit var viewBinding: ActivityMainBinding

    private val qrscanner = QRScanner()
    private var connectionManager = ConnectionManager.getInstance(this)
    private var camera: Camera? = null

    private var isConnecting = false
    private var pendingQrAfterPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        viewBinding.onboardingContainer.applySystemBarInsets()
        viewBinding.btnScannerClose.applySystemBarInsets(bottom = false)

        viewBinding.logReportButton.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }

        viewBinding.btnDesktopQr.setOnClickListener { openQrScanner() }

        viewBinding.btnDesktopUsb.setOnClickListener {
            if (hasUsbConnection()) {
                connectUSB()
            } else {
                showDesktopUsbSetupDialog()
            }
        }

        viewBinding.btnScannerClose.setOnClickListener { closeQrScanner() }

        viewBinding.btnObsMode.setOnClickListener {
            startActivity(Intent(this, DroidCamActivity::class.java))
        }

        viewBinding.btnHowItWorks.setOnClickListener {
            showHowItWorksDialog()
        }

        if (!hasCameraPermission()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1000)
        }
    }

    private fun showDesktopUsbSetupDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("USB desktop pairing")
            .setMessage(
                "1. Plug the phone into the PC with USB debugging on.\n" +
                "2. On the PC run:\n" +
                "   adb forward tcp:6969 tcp:6969\n" +
                "3. Open VCamdroid for Windows, then tap Connect."
            )
            .setPositiveButton("Connect") { _, _ -> connectUSB() }
            .setNeutralButton("Copy command") { _, _ ->
                copyText("adb forward tcp:6969 tcp:6969")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showHowItWorksDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("How to connect")
            .setMessage(
                "OBS Studio\n" +
                "Open OBS Studio on the phone. In OBS, add a DroidCam OBS source.\n" +
                "• Wi-Fi: same network, use the IP shown on the phone, port 4747.\n" +
                "• USB: adb forward tcp:4747 tcp:4747, then connect to 127.0.0.1:4747.\n\n" +
                "Windows webcam\n" +
                "• Wi-Fi: scan the QR code in the desktop app.\n" +
                "• USB: adb forward tcp:6969 tcp:6969, then tap Use USB."
            )
            .setPositiveButton("Got it", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        connectionManager = ConnectionManager.getInstance(this)
        if (viewBinding.overlay.visibility == View.VISIBLE) {
            startQrCamera()
            qrscanner.start()
        }
    }

    override fun onPause() {
        super.onPause()
        if (viewBinding.overlay.visibility != View.VISIBLE) {
            camera?.stop()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1000) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (pendingQrAfterPermission) {
                    pendingQrAfterPermission = false
                    openQrScanner()
                }
            } else {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Camera permission")
                    .setMessage("Camera access is needed to scan the desktop QR code and to stream.")
                    .setPositiveButton("Settings") { _, _ ->
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", packageName, null)
                        }
                        startActivity(intent)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    override fun onConnectionSuccessful(connectionMode: ConnectionManager.Mode) {
        runOnUiThread {
            isConnecting = false
            qrscanner.stop()
            camera?.stop()
            Logger.log("MAIN", "Connection successful $connectionMode")
            startActivity(Intent(this, StreamActivity::class.java))
        }
    }

    override fun onConnectionFailed(connectionMode: ConnectionManager.Mode) {
        runOnUiThread {
            isConnecting = false
            qrscanner.stop()
            Logger.log("MAIN", "Connection failed $connectionMode")
            if (connectionMode == ConnectionManager.Mode.USB) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Couldn't reach the desktop app")
                    .setMessage("Make sure VCamdroid is running on the PC and that USB forwarding is active:\nadb forward tcp:6969 tcp:6969")
                    .setPositiveButton("Retry") { _, _ -> connectUSB() }
                    .setNeutralButton("Copy command") { _, _ ->
                        copyText("adb forward tcp:6969 tcp:6969")
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Couldn't connect over Wi-Fi")
                    .setMessage("Check that the desktop app is showing a QR code and that both devices are on the same network.")
                    .setPositiveButton("Scan again") { _, _ -> openQrScanner() }
                    .setNegativeButton("Cancel") { _, _ -> closeQrScanner() }
                    .show()
            }
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun openQrScanner() {
        if (!hasCameraPermission()) {
            pendingQrAfterPermission = true
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1000)
            return
        }
        viewBinding.onboardingContainer.visibility = View.GONE
        viewBinding.viewFinder.visibility = View.VISIBLE
        viewBinding.overlay.visibility = View.VISIBLE
        viewBinding.btnScannerClose.visibility = View.VISIBLE
        startQrCamera()
        qrscanner.start()
    }

    private fun closeQrScanner() {
        qrscanner.stop()
        camera?.stop()
        viewBinding.overlay.visibility = View.GONE
        viewBinding.btnScannerClose.visibility = View.GONE
        viewBinding.viewFinder.visibility = View.GONE
        viewBinding.onboardingContainer.visibility = View.VISIBLE
    }

    private fun startQrCamera() {
        if (camera == null) {
            camera = Camera(
                viewBinding.viewFinder.surfaceProvider,
                ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888,
                ::processImage,
                this,
                this
            )
        }
        camera!!.start(Size(1280, 720), CameraSelector.DEFAULT_BACK_CAMERA)
    }

    private fun processImage(imageProxy: ImageProxy) {
        if (viewBinding.overlay.visibility != View.VISIBLE) {
            imageProxy.close()
            return
        }

        val overlayWidth = viewBinding.overlay.width
        val overlayHeight = viewBinding.overlay.height
        if (overlayWidth <= 0 || overlayHeight <= 0) {
            imageProxy.close()
            return
        }

        val overlaySize = if (viewBinding.overlay.size.width > 0 && viewBinding.overlay.size.height > 0) {
            viewBinding.overlay.size
        } else {
            Size(overlayWidth, overlayHeight)
        }

        qrscanner.launchScanTask(imageProxy, camera?.screenRectToImageRect(viewBinding.overlay.rect, overlaySize) ?: Rect()) { result ->
            runOnUiThread {
                if (result != null) {
                    qrscanner.stop()
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Connect over Wi-Fi")
                        .setMessage("Connect to ${result.address}:${result.port}?")
                        .setPositiveButton("Connect") { _, _ ->
                            connectionManager.connect(result.address, result.port)
                        }
                        .setNegativeButton("Cancel") { _, _ -> qrscanner.start() }
                        .show()
                }
            }
        }
    }

    private fun hasUsbConnection(): Boolean {
        val intent = applicationContext.registerReceiver(
            null,
            IntentFilter("android.hardware.usb.action.USB_STATE")
        )
        return intent?.getBooleanExtra("connected", false) == true
    }

    private fun connectUSB() {
        if (isConnecting) return
        isConnecting = true
        Toast.makeText(this, "Connecting over USB…", Toast.LENGTH_SHORT).show()
        connectionManager.connect(6969)
    }

    private fun copyText(value: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("ADB", value))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }
}
