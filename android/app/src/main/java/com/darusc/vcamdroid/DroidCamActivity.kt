package com.darusc.vcamdroid

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.hardware.camera2.CameraMetadata
import android.os.Bundle
import android.text.InputType
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import com.darusc.vcamdroid.databinding.ActivityDroidcamBinding
import com.darusc.vcamdroid.databinding.DialogDroidcamSettingsBinding
import com.darusc.vcamdroid.droidcam.DroidCamServer
import com.darusc.vcamdroid.droidcam.DroidCamSettings
import com.darusc.vcamdroid.droidcam.DroidCamStreamer
import com.darusc.vcamdroid.droidcam.MdnsAdvertiser
import com.darusc.vcamdroid.ai.AiTrackerEngine
import com.darusc.vcamdroid.service.StreamingService
import com.darusc.vcamdroid.util.Logger
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.math.roundToInt

class DroidCamActivity : AppCompatActivity(), SurfaceHolder.Callback, DroidCamServer.Listener {

    private lateinit var binding: ActivityDroidcamBinding
    private lateinit var droidCamServer: DroidCamServer
    private lateinit var mdnsAdvertiser: MdnsAdvertiser
    private lateinit var streamer: DroidCamStreamer
    private lateinit var settings: DroidCamSettings

    private var isBackCamera = true
    private var isDimMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settings = DroidCamSettings(this)
        if (settings.keepDeviceAwake) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        binding = ActivityDroidcamBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (settings.backgroundStreaming) {
            StreamingService.startService(this)
        }

        droidCamServer = DroidCamServer(this, settings.port, this)
        mdnsAdvertiser = MdnsAdvertiser(this)
        streamer = DroidCamStreamer(this, droidCamServer)

        binding.cameraPreview.holder.addCallback(this)

        setupStudioHUD()
        setupOpticalControls()
        setupTouchFocus()
        updateConnectionPill()

        checkPermissions()

        droidCamServer.start()
        if (settings.allowWifiDiscovery) {
            mdnsAdvertiser.startAdvertising(settings.port)
        }
    }

    private fun checkPermissions(): Boolean {
        val perms = mutableListOf(android.Manifest.permission.CAMERA)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            perms.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        val needed = perms.filter {
            androidx.core.content.ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            androidx.core.app.ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1001)
            return false
        }
        return true
    }

    private var hideFeedbackRunnable: Runnable? = null

    private fun showGestureFeedbackToast(message: String) {
        binding.txtGestureFeedback.text = message
        binding.txtGestureFeedback.visibility = View.VISIBLE
        binding.txtGestureFeedback.alpha = 1.0f

        hideFeedbackRunnable?.let { binding.txtGestureFeedback.removeCallbacks(it) }
        val r = Runnable {
            binding.txtGestureFeedback.animate()
                .alpha(0f)
                .setDuration(400)
                .withEndAction { binding.txtGestureFeedback.visibility = View.GONE }
                .start()
        }
        hideFeedbackRunnable = r
        binding.txtGestureFeedback.postDelayed(r, 2200)
    }

    private fun setupStudioHUD() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnDim.setOnClickListener {
            toggleDimMode(true)
        }

        binding.dimOverlay.setOnClickListener {
            toggleDimMode(false)
        }

        binding.btnSettings.setOnClickListener {
            showStudioSettingsSheet()
        }

        // AI Tracking Toggle Button
        fun updateAiTrackUI(enabled: Boolean) {
            if (enabled) {
                binding.btnAiTrack.text = "AI ON"
                binding.btnAiTrack.setBackgroundColor(Color.parseColor("#00E676"))
                binding.btnAiTrack.setTextColor(Color.BLACK)
            } else {
                binding.btnAiTrack.text = "AI OFF"
                binding.btnAiTrack.setBackgroundColor(Color.parseColor("#2E303E"))
                binding.btnAiTrack.setTextColor(Color.WHITE)
                binding.faceReticle.visibility = View.GONE
            }
        }

        updateAiTrackUI(settings.isAiTrackingEnabled)

        binding.btnAiTrack.setOnClickListener {
            val newState = streamer.toggleAiTracking()
            updateAiTrackUI(newState)
            showGestureFeedbackToast(if (newState) "🤖 AI Tracking Enabled" else "⏸️ AI Tracking Paused")
        }

        // Framing Mode (16:9 Landscape vs 9:16 Vertical)
        fun updateFramingModeUI(mode: AiTrackerEngine.FramingMode) {
            binding.btnFramingMode.text = if (mode == AiTrackerEngine.FramingMode.PORTRAIT_9_16) "9:16" else "16:9"
            val label = if (mode == AiTrackerEngine.FramingMode.PORTRAIT_9_16) "Vertical 9:16 (Shorts/Reels)" else "Landscape 16:9"
            showGestureFeedbackToast("📐 Framing: $label")
        }

        binding.btnFramingMode.text = settings.aiFramingMode
        binding.btnFramingMode.setOnClickListener {
            val nextMode = streamer.toggleFramingMode()
            updateFramingModeUI(nextMode)
        }

        // Callbacks for Gestures and Face Tracking
        streamer.onAiTrackingStateChanged = { active ->
            runOnUiThread {
                updateAiTrackUI(active)
            }
        }

        streamer.onGestureFeedback = { message ->
            runOnUiThread {
                showGestureFeedbackToast(message)
            }
        }

        streamer.onAiFaceTrackingUpdate = { _, _ ->
            runOnUiThread {
                binding.faceReticle.visibility = View.GONE
            }
        }
    }

    private fun showCard(activeCard: CardView?, activeButton: Button?) {
        val allCards = listOf(binding.isoCard, binding.evCard, binding.zoomCard, binding.focusCard)
        val allButtons = listOf(binding.btnIsoToggle, binding.btnEvToggle, binding.btnZoomToggle, binding.btnFocusToggle)

        val isAlreadyOpen = activeCard?.visibility == View.VISIBLE

        allCards.forEach { it.visibility = View.GONE }
        allButtons.forEach {
            it.setBackgroundColor(Color.parseColor("#252632"))
            it.setTextColor(Color.WHITE)
        }

        if (!isAlreadyOpen && activeCard != null && activeButton != null) {
            activeCard.visibility = View.VISIBLE
            activeButton.setBackgroundColor(Color.parseColor("#3F51B5"))
            activeButton.setTextColor(Color.WHITE)
        }
    }

    private fun showDirectNumericEditDialog(
        title: String,
        currentValueStr: String,
        unitLabel: String,
        minVal: Float,
        maxVal: Float,
        isInteger: Boolean = false,
        onApply: (Float) -> Unit
    ) {
        val context = this
        val builder = AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 20)
        }

        val rangeText = TextView(context).apply {
            text = if (isInteger) "Allowed: ${minVal.toInt()} - ${maxVal.toInt()} $unitLabel" else "Allowed: $minVal - $maxVal $unitLabel"
            setTextColor(Color.parseColor("#9E9E9E"))
            textSize = 13f
            setPadding(0, 0, 0, 20)
        }
        container.addView(rangeText)

        val input = EditText(context).apply {
            setText(currentValueStr)
            setSelectAllOnFocus(true)
            setTextColor(Color.parseColor("#00E676"))
            textSize = 22f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#1E1F2B"))
            inputType = if (isInteger) {
                InputType.TYPE_CLASS_NUMBER
            } else {
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            }
            setPadding(30, 30, 30, 30)
        }
        container.addView(input)

        builder.setTitle(title)
        builder.setView(container)
        builder.setPositiveButton("Apply") { dialog, _ ->
            val typed = input.text.toString().trim()
            val num = typed.toFloatOrNull()
            if (num != null) {
                val clamped = num.coerceIn(minVal, maxVal)
                onApply(clamped)
            }
            dialog.dismiss()
        }
        builder.setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }

        val alert = builder.create()
        alert.show()
        input.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun setupOpticalControls() {
        // 1. Lens Switch
        binding.btnSwitchLens.setOnClickListener {
            isBackCamera = !isBackCamera
            val lensName = if (isBackCamera) "Back" else "Front"
            binding.btnSwitchLens.text = "Lens ($lensName)"
            if (droidCamServer.isStreaming) {
                streamer.stopStream()
                streamer.startStream(streamer.currentFormat, streamer.currentWidth, streamer.currentHeight, settings.targetFps, isBackCamera)
            }
        }

        // 2. Torch / Flash
        binding.btnTorch.setOnClickListener {
            val newState = !streamer.isTorchOn
            streamer.setTorch(newState)
            if (newState) {
                binding.btnTorch.text = "Flash ON"
                binding.btnTorch.setBackgroundColor(Color.parseColor("#FFD54F"))
                binding.btnTorch.setTextColor(Color.BLACK)
            } else {
                binding.btnTorch.text = "Flash OFF"
                binding.btnTorch.setBackgroundColor(Color.parseColor("#252632"))
                binding.btnTorch.setTextColor(Color.WHITE)
            }
        }

        // 3. White Balance Cycle
        val awbModes = listOf(
            Pair(CameraMetadata.CONTROL_AWB_MODE_AUTO, "AWB: Auto"),
            Pair(CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT, "AWB: Sun"),
            Pair(CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT, "AWB: Cloud"),
            Pair(CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT, "AWB: Fluor"),
            Pair(CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT, "AWB: Tungsten")
        )
        var awbIndex = 0
        binding.btnAwb.setOnClickListener {
            awbIndex = (awbIndex + 1) % awbModes.size
            val (mode, label) = awbModes[awbIndex]
            streamer.setAwbMode(mode)
            binding.btnAwb.text = label
        }

        // 4. ISO Sensitivity (Smooth Non-Snap Slider + Direct Entry)
        binding.btnIsoToggle.setOnClickListener {
            showCard(binding.isoCard, binding.btnIsoToggle)
        }

        binding.seekIso.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val frac = progress.toFloat() / 10000f
                    val iso = (streamer.minIso + frac * (streamer.maxIso - streamer.minIso)).roundToInt()
                    streamer.setIso(iso)
                    binding.txtIsoValue.text = "ISO $iso"
                    binding.btnIsoToggle.text = "ISO $iso"
                    binding.btnIsoAuto.text = "MANUAL"
                    binding.btnIsoAuto.setBackgroundColor(Color.parseColor("#FF9800"))
                    binding.btnIsoAuto.setTextColor(Color.BLACK)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.btnIsoAuto.setOnClickListener {
            val willBeAuto = streamer.isManualIso
            streamer.setAutoIso(willBeAuto)
            if (willBeAuto) {
                binding.btnIsoAuto.text = "AUTO"
                binding.btnIsoAuto.setBackgroundColor(Color.parseColor("#00E676"))
                binding.btnIsoAuto.setTextColor(Color.BLACK)
                binding.txtIsoValue.text = "ISO Auto [${streamer.actualSensorIso}]"
                binding.btnIsoToggle.text = "ISO Auto"
            } else {
                binding.btnIsoAuto.text = "MANUAL"
                binding.btnIsoAuto.setBackgroundColor(Color.parseColor("#FF9800"))
                binding.btnIsoAuto.setTextColor(Color.BLACK)
                streamer.setIso(streamer.currentIso)
                binding.txtIsoValue.text = "ISO ${streamer.currentIso}"
                binding.btnIsoToggle.text = "ISO ${streamer.currentIso}"
            }
        }

        binding.txtIsoValue.setOnClickListener {
            showDirectNumericEditDialog(
                title = "Direct ISO Entry",
                currentValueStr = "${streamer.currentIso}",
                unitLabel = "ISO",
                minVal = streamer.minIso.toFloat(),
                maxVal = streamer.maxIso.toFloat(),
                isInteger = true
            ) { value ->
                val iso = value.roundToInt()
                streamer.setIso(iso)
                val frac = (iso - streamer.minIso).toFloat() / (streamer.maxIso - streamer.minIso).toFloat()
                binding.seekIso.progress = (frac * 10000).toInt()
                binding.txtIsoValue.text = "ISO $iso"
                binding.btnIsoToggle.text = "ISO $iso"
                binding.btnIsoAuto.text = "MANUAL"
                binding.btnIsoAuto.setBackgroundColor(Color.parseColor("#FF9800"))
                binding.btnIsoAuto.setTextColor(Color.BLACK)
            }
        }

        // 5. EV Compensation (Smooth Non-Snap Slider + Direct Entry)
        binding.btnEvToggle.setOnClickListener {
            showCard(binding.evCard, binding.btnEvToggle)
        }

        binding.seekEv.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val min = streamer.minExposureCompensation
                    val max = streamer.maxExposureCompensation
                    val frac = progress.toFloat() / 10000f
                    val evVal = (min + frac * (max - min)).roundToInt()
                    streamer.setExposureCompensation(evVal)
                    val evReal = evVal * streamer.exposureCompensationStep
                    binding.txtEvValue.text = String.format("%+.1f EV", evReal)
                    binding.btnEvToggle.text = String.format("EV %+.1f", evReal)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.txtEvValue.setOnClickListener {
            val curEvReal = streamer.currentExposureCompensation * streamer.exposureCompensationStep
            val minEvReal = streamer.minExposureCompensation * streamer.exposureCompensationStep
            val maxEvReal = streamer.maxExposureCompensation * streamer.exposureCompensationStep
            showDirectNumericEditDialog(
                title = "Direct EV Entry",
                currentValueStr = String.format("%.1f", curEvReal),
                unitLabel = "EV",
                minVal = minEvReal,
                maxVal = maxEvReal,
                isInteger = false
            ) { value ->
                val step = if (streamer.exposureCompensationStep > 0) streamer.exposureCompensationStep else 0.5f
                val evSteps = (value / step).roundToInt().coerceIn(streamer.minExposureCompensation, streamer.maxExposureCompensation)
                streamer.setExposureCompensation(evSteps)
                val evReal = evSteps * step
                val frac = (evSteps - streamer.minExposureCompensation).toFloat() / (streamer.maxExposureCompensation - streamer.minExposureCompensation).toFloat()
                binding.seekEv.progress = (frac * 10000).toInt()
                binding.txtEvValue.text = String.format("%+.1f EV", evReal)
                binding.btnEvToggle.text = String.format("EV %+.1f", evReal)
            }
        }

        binding.btnAeLock.setOnClickListener {
            val newLock = !streamer.isAeLocked
            streamer.setAeLock(newLock)
            if (newLock) {
                binding.btnAeLock.text = "LOCKED"
                binding.btnAeLock.setBackgroundColor(Color.parseColor("#E53935"))
            } else {
                binding.btnAeLock.text = "AE LOCK"
                binding.btnAeLock.setBackgroundColor(Color.parseColor("#2E303E"))
            }
        }

        binding.btnEvReset.setOnClickListener {
            streamer.setExposureCompensation(0)
            val frac = (0 - streamer.minExposureCompensation).toFloat() / (streamer.maxExposureCompensation - streamer.minExposureCompensation).toFloat()
            binding.seekEv.progress = (frac * 10000).toInt()
            binding.txtEvValue.text = "0.0 EV"
            binding.btnEvToggle.text = "EV 0.0"
        }

        binding.btnEvMinus.setOnClickListener {
            val cur = streamer.currentExposureCompensation
            val next = (cur - 1).coerceAtLeast(streamer.minExposureCompensation)
            streamer.setExposureCompensation(next)
            val evReal = next * streamer.exposureCompensationStep
            val frac = (next - streamer.minExposureCompensation).toFloat() / (streamer.maxExposureCompensation - streamer.minExposureCompensation).toFloat()
            binding.seekEv.progress = (frac * 10000).toInt()
            binding.txtEvValue.text = String.format("%+.1f EV", evReal)
            binding.btnEvToggle.text = String.format("EV %+.1f", evReal)
        }

        binding.btnEvPlus.setOnClickListener {
            val cur = streamer.currentExposureCompensation
            val next = (cur + 1).coerceAtMost(streamer.maxExposureCompensation)
            streamer.setExposureCompensation(next)
            val evReal = next * streamer.exposureCompensationStep
            val frac = (next - streamer.minExposureCompensation).toFloat() / (streamer.maxExposureCompensation - streamer.minExposureCompensation).toFloat()
            binding.seekEv.progress = (frac * 10000).toInt()
            binding.txtEvValue.text = String.format("%+.1f EV", evReal)
            binding.btnEvToggle.text = String.format("EV %+.1f", evReal)
        }

        // 6. Zoom (Smooth Non-Snap Slider + Direct Entry)
        binding.btnZoomToggle.setOnClickListener {
            showCard(binding.zoomCard, binding.btnZoomToggle)
        }

        binding.seekZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val minZ = streamer.minZoomFactor
                    val maxZ = streamer.maxZoomFactor
                    val frac = progress.toFloat() / 10000f
                    val z = minZ + frac * (maxZ - minZ)
                    streamer.setZoom(z)
                    binding.txtZoomValue.text = String.format("%.2fx", z)
                    binding.btnZoomToggle.text = String.format("Zoom %.1fx", z)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.txtZoomValue.setOnClickListener {
            showDirectNumericEditDialog(
                title = "Direct Zoom Entry",
                currentValueStr = String.format("%.2f", streamer.currentZoom),
                unitLabel = "x",
                minVal = streamer.minZoomFactor,
                maxVal = streamer.maxZoomFactor,
                isInteger = false
            ) { value ->
                streamer.setZoom(value)
                val frac = (value - streamer.minZoomFactor) / (streamer.maxZoomFactor - streamer.minZoomFactor)
                binding.seekZoom.progress = (frac * 10000).toInt()
                binding.txtZoomValue.text = String.format("%.2fx", value)
                binding.btnZoomToggle.text = String.format("Zoom %.1fx", value)
            }
        }

        binding.btnZoom1x.setOnClickListener {
            streamer.setZoom(1.0f)
            binding.seekZoom.progress = 0
            binding.txtZoomValue.text = "1.0x"
            binding.btnZoomToggle.text = "Zoom 1.0x"
        }

        binding.btnZoom2x.setOnClickListener {
            val z = 2.0f.coerceIn(streamer.minZoomFactor, streamer.maxZoomFactor)
            streamer.setZoom(z)
            val frac = (z - streamer.minZoomFactor) / (streamer.maxZoomFactor - streamer.minZoomFactor)
            binding.seekZoom.progress = (frac * 10000).toInt()
            binding.txtZoomValue.text = String.format("%.1fx", z)
            binding.btnZoomToggle.text = String.format("Zoom %.1fx", z)
        }

        binding.btnZoom5x.setOnClickListener {
            val z = 5.0f.coerceIn(streamer.minZoomFactor, streamer.maxZoomFactor)
            streamer.setZoom(z)
            val frac = (z - streamer.minZoomFactor) / (streamer.maxZoomFactor - streamer.minZoomFactor)
            binding.seekZoom.progress = (frac * 10000).toInt()
            binding.txtZoomValue.text = String.format("%.1fx", z)
            binding.btnZoomToggle.text = String.format("Zoom %.1fx", z)
        }

        // 7. Focus Mode & Distance (Smooth Focus Puller + Direct Entry)
        binding.btnFocusToggle.setOnClickListener {
            showCard(binding.focusCard, binding.btnFocusToggle)
        }

        binding.seekFocus.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val frac = progress.toFloat() / 10000f
                    val dist = frac * streamer.maxFocusDistance
                    streamer.setFocusDistance(dist)
                    binding.txtFocusValue.text = String.format("%.2f dpt", dist)
                    binding.btnFocusToggle.text = "MF: Active"
                    binding.btnFocusAuto.text = "MANUAL"
                    binding.btnFocusAuto.setBackgroundColor(Color.parseColor("#FF9800"))
                    binding.btnFocusAuto.setTextColor(Color.BLACK)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.txtFocusValue.setOnClickListener {
            showDirectNumericEditDialog(
                title = "Direct Focus Entry",
                currentValueStr = String.format("%.2f", streamer.currentFocusDistance),
                unitLabel = "diopters (0 = inf)",
                minVal = 0.0f,
                maxVal = streamer.maxFocusDistance,
                isInteger = false
            ) { value ->
                streamer.setFocusDistance(value)
                val frac = if (streamer.maxFocusDistance > 0f) value / streamer.maxFocusDistance else 0f
                binding.seekFocus.progress = (frac * 10000).toInt()
                binding.txtFocusValue.text = String.format("%.2f dpt", value)
                binding.btnFocusToggle.text = "MF: Active"
                binding.btnFocusAuto.text = "MANUAL"
                binding.btnFocusAuto.setBackgroundColor(Color.parseColor("#FF9800"))
                binding.btnFocusAuto.setTextColor(Color.BLACK)
            }
        }

        var focusModeIdx = 0
        val focusModes = listOf(
            Pair("AF-C", CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO),
            Pair("AF-S", CameraMetadata.CONTROL_AF_MODE_AUTO),
            Pair("MACRO", CameraMetadata.CONTROL_AF_MODE_MACRO)
        )
        binding.btnFocusAuto.setOnClickListener {
            focusModeIdx = (focusModeIdx + 1) % focusModes.size
            val (name, mode) = focusModes[focusModeIdx]
            streamer.setAutoAf(mode)
            binding.btnFocusAuto.text = name
            binding.btnFocusAuto.setBackgroundColor(Color.parseColor("#00E676"))
            binding.btnFocusAuto.setTextColor(Color.BLACK)
            binding.btnFocusToggle.text = "Focus: $name"
            binding.txtFocusValue.text = "$name"
        }
    }

    private fun setupTouchFocus() {
        binding.cameraPreview.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val x = event.x
                val y = event.y

                // Animate Focus Ring
                binding.focusRing.x = x - (binding.focusRing.width / 2)
                binding.focusRing.y = y - (binding.focusRing.height / 2)
                binding.focusRing.visibility = View.VISIBLE
                binding.focusRing.alpha = 1.0f
                binding.focusRing.scaleX = 1.4f
                binding.focusRing.scaleY = 1.4f

                val scaleDownX = ObjectAnimator.ofFloat(binding.focusRing, "scaleX", 1.0f)
                val scaleDownY = ObjectAnimator.ofFloat(binding.focusRing, "scaleY", 1.0f)
                val fadeOut = ObjectAnimator.ofFloat(binding.focusRing, "alpha", 0.0f).apply {
                    startDelay = 800
                    duration = 300
                }

                AnimatorSet().apply {
                    playTogether(scaleDownX, scaleDownY)
                    play(fadeOut).after(scaleDownX)
                    start()
                }

                streamer.triggerTapToFocus(x, y, binding.cameraPreview.width, binding.cameraPreview.height)
                true
            } else {
                false
            }
        }
    }

    private fun showStudioSettingsSheet() {
        val dialog = BottomSheetDialog(this)
        val sheetBinding = DialogDroidcamSettingsBinding.inflate(layoutInflater)
        dialog.setContentView(sheetBinding.root)

        // Resolutions
        val curW = settings.targetResolutionWidth
        val curH = settings.targetResolutionHeight
        sheetBinding.txtCurrentResolution.text = "Target: ${settings.getResolutionLabel()}"

        val resMap = mapOf(
            sheetBinding.btnRes4k to Pair(3840, 2160),
            sheetBinding.btnRes1440 to Pair(2560, 1440),
            sheetBinding.btnRes1080 to Pair(1920, 1080),
            sheetBinding.btnRes720 to Pair(1280, 720),
            sheetBinding.btnRes480 to Pair(640, 480)
        )

        fun updateResolutionUI(selectedW: Int, selectedH: Int) {
            settings.targetResolutionWidth = selectedW
            settings.targetResolutionHeight = selectedH
            sheetBinding.txtCurrentResolution.text = "Target: ${settings.getResolutionLabel()}"

            resMap.forEach { (btn, res) ->
                if (res.first == selectedW && res.second == selectedH) {
                    btn.setBackgroundColor(Color.parseColor("#00E676"))
                    btn.setTextColor(Color.BLACK)
                } else {
                    btn.setBackgroundColor(Color.parseColor("#2E303E"))
                    btn.setTextColor(Color.WHITE)
                }
            }

            if (droidCamServer.isStreaming) {
                streamer.stopStream()
                streamer.startStream(streamer.currentFormat, selectedW, selectedH, settings.targetFps, isBackCamera)
                val mode = if (isUsbConnection) "USB" else "Wi-Fi"
                binding.txtSubStatus.text = "OBS Active ($mode): ${selectedW}x${selectedH} ${streamer.currentFormat} @ ${settings.targetFps}fps"
            }
        }
        updateResolutionUI(curW, curH)

        resMap.forEach { (btn, res) ->
            btn.setOnClickListener { updateResolutionUI(res.first, res.second) }
        }

        sheetBinding.btnProbeResolutions.setOnClickListener {
            showCameraProbeDialog()
        }

        // Bitrates
        val currentKbps = settings.targetBitrateKbps
        sheetBinding.txtCurrentBitrate.text = "Target Bitrate: ${currentKbps / 1000.0} Mbps (CBR)"

        val bitrateMap: Map<Button, Int> = mapOf<Button, Int>(
            sheetBinding.btnBitrate25 to 2500,
            sheetBinding.btnBitrate4 to 4000,
            sheetBinding.btnBitrate8 to 8000,
            sheetBinding.btnBitrate12 to 12000,
            sheetBinding.btnBitrate20 to 20000,
            sheetBinding.btnBitrate40 to 40000
        )

        fun updateBitrateUI(selectedKbps: Int) {
            settings.targetBitrateKbps = selectedKbps
            sheetBinding.txtCurrentBitrate.text = "Target Bitrate: ${selectedKbps / 1000.0} Mbps (CBR)"
            bitrateMap.forEach { (btn, kbps) ->
                if (kbps == selectedKbps) {
                    btn.setBackgroundColor(Color.parseColor("#00E676"))
                    btn.setTextColor(Color.BLACK)
                } else {
                    btn.setBackgroundColor(Color.parseColor("#2E303E"))
                    btn.setTextColor(Color.WHITE)
                }
            }
        }
        updateBitrateUI(currentKbps)

        bitrateMap.forEach { (btn, kbps) ->
            btn.setOnClickListener { updateBitrateUI(kbps) }
        }

        // Target FPS
        val currentFps = settings.targetFps
        sheetBinding.txtCurrentFps.text = "Target: $currentFps FPS"

        val fpsMap = mapOf(
            sheetBinding.btnFps24 to 24,
            sheetBinding.btnFps30 to 30,
            sheetBinding.btnFps48 to 48,
            sheetBinding.btnFps60 to 60
        )

        fun updateFpsUI(selectedFps: Int) {
            settings.targetFps = selectedFps
            sheetBinding.txtCurrentFps.text = "Target: $selectedFps FPS (Sensor Timing: ${1000 / selectedFps}ms)"
            fpsMap.forEach { (btn, fps) ->
                if (fps == selectedFps) {
                    btn.setBackgroundColor(Color.parseColor("#00E676"))
                    btn.setTextColor(Color.BLACK)
                } else {
                    btn.setBackgroundColor(Color.parseColor("#2E303E"))
                    btn.setTextColor(Color.WHITE)
                }
            }
        }
        updateFpsUI(currentFps)

        fpsMap.forEach { (btn, fps) ->
            btn.setOnClickListener { updateFpsUI(fps) }
        }

        // Anti-Flicker
        val flickerMap = mapOf(
            sheetBinding.btnFlickerAuto to "auto",
            sheetBinding.btnFlicker50 to "50hz",
            sheetBinding.btnFlicker60 to "60hz",
            sheetBinding.btnFlickerOff to "off"
        )
        fun updateFlickerUI(mode: String) {
            settings.antiFlickerMode = mode
            streamer.setAntiFlicker(mode)
            flickerMap.forEach { (btn, m) ->
                if (m.equals(mode, ignoreCase = true)) {
                    btn.setBackgroundColor(Color.parseColor("#00E676"))
                    btn.setTextColor(Color.BLACK)
                } else {
                    btn.setBackgroundColor(Color.parseColor("#2E303E"))
                    btn.setTextColor(Color.WHITE)
                }
            }
        }
        updateFlickerUI(settings.antiFlickerMode)

        flickerMap.forEach { (btn, mode) ->
            btn.setOnClickListener { updateFlickerUI(mode) }
        }

        // Switches
        sheetBinding.switchScreenOffVideo.isChecked = settings.keepVideoActiveScreenOff
        sheetBinding.switchScreenOffVideo.setOnCheckedChangeListener { _, isChecked ->
            settings.keepVideoActiveScreenOff = isChecked
        }

        sheetBinding.switchKeepAwake.isChecked = settings.keepDeviceAwake
        sheetBinding.switchKeepAwake.setOnCheckedChangeListener { _, isChecked ->
            settings.keepDeviceAwake = isChecked
            if (isChecked) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        sheetBinding.switchTally.isChecked = settings.showTallyIndicator
        sheetBinding.switchTally.setOnCheckedChangeListener { _, isChecked ->
            settings.showTallyIndicator = isChecked
            binding.txtTally.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        sheetBinding.btnCloseSettings.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showCameraProbeDialog() {
        val (backRes, frontRes) = com.darusc.vcamdroid.video.queryDeviceResolutions(this)
        val items = mutableListOf<String>()
        val resList = mutableListOf<Pair<Int, Int>>()

        items.add("--- REAR CAMERA RESOLUTIONS ---")
        resList.add(Pair(0, 0))
        for (r in backRes) {
            val aspect = String.format("%.2f:1", r.first.toFloat() / r.second.toFloat())
            items.add("Back: ${r.first} x ${r.second} ($aspect)")
            resList.add(r)
        }

        items.add("--- FRONT CAMERA RESOLUTIONS ---")
        resList.add(Pair(0, 0))
        for (r in frontRes) {
            val aspect = String.format("%.2f:1", r.first.toFloat() / r.second.toFloat())
            items.add("Front: ${r.first} x ${r.second} ($aspect)")
            resList.add(r)
        }

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Camera Hardware Resolution Probe")
            .setItems(items.toTypedArray()) { dialog, which ->
                val selected = resList[which]
                if (selected.first > 0 && selected.second > 0) {
                    settings.targetResolutionWidth = selected.first
                    settings.targetResolutionHeight = selected.second
                    if (droidCamServer.isStreaming) {
                        streamer.stopStream()
                        streamer.startStream(streamer.currentFormat, selected.first, selected.second, settings.targetFps, isBackCamera)
                        val mode = if (isUsbConnection) "USB" else "Wi-Fi"
                        binding.txtSubStatus.text = "OBS Active ($mode): ${selected.first}x${selected.second} ${streamer.currentFormat} @ ${settings.targetFps}fps"
                    } else {
                        updateConnectionPill()
                    }
                    android.widget.Toast.makeText(this, "Target resolution set to ${selected.first}x${selected.second}", android.widget.Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setPositiveButton("Close", null)
            .show()
    }

    private fun toggleDimMode(enable: Boolean) {
        isDimMode = enable
        val lp = window.attributes
        if (enable) {
            binding.dimOverlay.visibility = View.VISIBLE
            lp.screenBrightness = 0.01f // AMOLED minimum brightness
        } else {
            binding.dimOverlay.visibility = View.GONE
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        window.attributes = lp
    }

    private fun updateConnectionPill() {
        val ip = getLocalIpAddress() ?: "Wi-Fi Disconnected"
        binding.txtConnectionPill.text = "$ip : ${settings.port}"
        binding.txtSubStatus.text = "Wi-Fi LAN & USB ADB Forward (127.0.0.1:${settings.port}) • ${settings.targetResolutionWidth}x${settings.targetResolutionHeight}"
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) { }
        return null
    }

    private fun adjustPreviewAspectRatio(streamWidth: Int, streamHeight: Int) {
        binding.root.post {
            val rootW = binding.root.width
            val rootH = binding.root.height
            if (rootW <= 0 || rootH <= 0 || streamWidth <= 0 || streamHeight <= 0) return@post

            val aspect = if (streamWidth > streamHeight) {
                streamHeight.toFloat() / streamWidth.toFloat()
            } else {
                streamWidth.toFloat() / streamHeight.toFloat()
            }

            var targetW = rootW
            var targetH = (rootW / aspect).roundToInt()

            if (targetH > rootH) {
                targetH = rootH
                targetW = (rootH * aspect).roundToInt()
            }

            val lp = binding.cameraPreview.layoutParams
            if (lp.width != targetW || lp.height != targetH) {
                lp.width = targetW
                lp.height = targetH
                binding.cameraPreview.layoutParams = lp
                binding.cameraPreview.holder.setFixedSize(targetW, targetH)
            }
        }
    }

    // --- SurfaceHolder.Callback ---

    override fun surfaceCreated(holder: SurfaceHolder) {
        adjustPreviewAspectRatio(settings.targetResolutionWidth, settings.targetResolutionHeight)
        streamer.onScreenOn(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        streamer.onScreenOff()
    }

    // --- DroidCamServer.Listener ---

    private var isUsbConnection = false

    override fun onVideoStreamStarted(format: String, width: Int, height: Int) {
        val mode = if (isUsbConnection) "USB" else "Wi-Fi"
        runOnUiThread {
            adjustPreviewAspectRatio(width, height)
            binding.txtSubStatus.text = "OBS Active ($mode): ${width}x${height} $format @ ${settings.targetFps}fps"
            binding.txtSubStatus.setTextColor(Color.parseColor("#00E676"))
        }
        streamer.startStream(format, width, height, settings.targetFps, isBackCamera)
    }

    override fun onVideoStreamStopped() {
        runOnUiThread {
            binding.txtSubStatus.text = "Ready for OBS Studio • Standby: ${settings.getResolutionLabel()}"
            binding.txtSubStatus.setTextColor(Color.parseColor("#9E9E9E"))
            updateTallyBadge("idle")
        }
        streamer.stopStream()
    }

    override fun onTallyChanged(tallyState: String) {
        runOnUiThread {
            updateTallyBadge(tallyState)
        }
    }

    override fun onClientConnected(remoteAddress: String) {
        isUsbConnection = remoteAddress.contains("127.0.0.1") || remoteAddress.contains("localhost")
        runOnUiThread {
            if (isUsbConnection) {
                binding.txtConnectionPill.text = "USB : ${settings.port}"
                binding.txtSubStatus.text = "Connected via Wired USB (ADB)"
                binding.txtSubStatus.setTextColor(Color.parseColor("#FFD54F"))
            } else {
                updateConnectionPill()
                binding.txtSubStatus.text = "Connected via Wi-Fi ($remoteAddress)"
                binding.txtSubStatus.setTextColor(Color.parseColor("#64B5F6"))
            }
        }
    }

    override fun onClientDisconnected() {
        isUsbConnection = false
        runOnUiThread {
            updateConnectionPill()
            binding.txtSubStatus.text = "Ready for OBS Studio • Standby: ${settings.getResolutionLabel()}"
            binding.txtSubStatus.setTextColor(Color.parseColor("#9E9E9E"))
        }
    }

    private fun updateTallyBadge(state: String) {
        if (!settings.showTallyIndicator) {
            binding.txtTally.visibility = View.GONE
            return
        }
        binding.txtTally.visibility = View.VISIBLE
        when (state.lowercase()) {
            "program" -> {
                binding.txtTally.text = "LIVE: ON AIR"
                binding.txtTally.setBackgroundColor(Color.parseColor("#E53935"))
                binding.dimTallyStatus.text = "LIVE: ON AIR"
                binding.dimTallyStatus.setTextColor(Color.parseColor("#B71C1C"))
            }
            "preview" -> {
                binding.txtTally.text = "PREVIEW"
                binding.txtTally.setBackgroundColor(Color.parseColor("#F57F17"))
                binding.dimTallyStatus.text = "PREVIEW"
                binding.dimTallyStatus.setTextColor(Color.parseColor("#E65100"))
            }
            else -> {
                binding.txtTally.text = "STANDBY"
                binding.txtTally.setBackgroundColor(Color.parseColor("#2E303E"))
                binding.dimTallyStatus.text = "STANDBY"
                binding.dimTallyStatus.setTextColor(Color.parseColor("#424242"))
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            mdnsAdvertiser.stopAdvertising()
            droidCamServer.stop()
            streamer.stopStream()
            StreamingService.stopService(this)
        }
    }
}
