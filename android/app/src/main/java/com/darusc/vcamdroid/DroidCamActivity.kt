package com.darusc.vcamdroid

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
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
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.view.WindowCompat
import com.darusc.vcamdroid.databinding.ActivityDroidcamBinding
import com.darusc.vcamdroid.databinding.DialogConnectionDetailsBinding
import com.darusc.vcamdroid.databinding.DialogDroidcamSettingsBinding
import com.darusc.vcamdroid.droidcam.DroidCamServer
import com.darusc.vcamdroid.droidcam.DroidCamSettings
import com.darusc.vcamdroid.droidcam.DroidCamStreamer
import com.darusc.vcamdroid.droidcam.MdnsAdvertiser
import com.darusc.vcamdroid.ai.AiTrackerEngine
import com.darusc.vcamdroid.service.StreamingService
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.TextureView
import androidx.core.content.ContextCompat
import com.darusc.vcamdroid.databinding.DialogSensorProbeBinding
import com.darusc.vcamdroid.databinding.ItemSensorResolutionBinding
import com.darusc.vcamdroid.util.applySystemBarInsets
import com.darusc.vcamdroid.util.formatBitrateKbps
import com.darusc.vcamdroid.video.AutoFitTextureView
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class DroidCamActivity : AppCompatActivity(), TextureView.SurfaceTextureListener, DroidCamServer.Listener {

    private lateinit var binding: ActivityDroidcamBinding
    private lateinit var droidCamServer: DroidCamServer
    private lateinit var mdnsAdvertiser: MdnsAdvertiser
    private lateinit var streamer: DroidCamStreamer
    private lateinit var settings: DroidCamSettings

    private var isBackCamera = true
    private var isDimMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        settings = DroidCamSettings(this)
        if (settings.keepDeviceAwake) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        binding = ActivityDroidcamBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.topBar.applySystemBarInsets(bottom = false)
        binding.bottomControlPalette.applySystemBarInsets(top = false)

        if (settings.backgroundStreaming) {
            StreamingService.startService(this, "Waiting for OBS")
        }

        droidCamServer = DroidCamServer(this, settings.port, this)
        mdnsAdvertiser = MdnsAdvertiser(this)
        streamer = DroidCamStreamer(this, droidCamServer)

        binding.cameraPreview.surfaceTextureListener = this
        adjustPreviewAspectRatio(settings.targetResolutionWidth, settings.targetResolutionHeight)

        setupStudioHUD()
        setupOpticalControls()
        setupTouchFocus()
        updateConnectionPill()
        syncHudToCapabilities()

        streamer.onCapabilitiesChanged = {
            runOnUiThread {
                syncHudToCapabilities()
                configureTransform(binding.cameraPreview.width, binding.cameraPreview.height)
            }
        }

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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 1001) return
        val cameraGranted = androidx.core.content.ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (cameraGranted) {
            previewSurface?.let { streamer.onScreenOn(it) }
        }
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

        binding.connectionPillContainer.setOnClickListener {
            showConnectionDetailsDialog()
        }

        binding.btnDim.setOnClickListener {
            toggleDimMode(true)
        }

        binding.dimOverlay.setOnClickListener {
            toggleDimMode(false)
        }

        binding.btnSensorProbeQuick.setOnClickListener {
            showCameraProbeDialog()
        }

        binding.btnBottomSettings.setOnClickListener {
            showStudioSettingsSheet()
        }

        binding.btnCenterAction.setOnClickListener {
            showConnectionDetailsDialog()
        }

        fun updateAiTrackUI(enabled: Boolean) {
            binding.valPillAi.text = if (enabled) "ON" else "OFF"
            binding.lblPillAi.setTextColor(
                ContextCompat.getColor(this, if (enabled) R.color.accent_green else R.color.text_secondary)
            )
            binding.btnAiTrack.setBackgroundResource(
                if (enabled) R.drawable.bg_param_pill_selected else R.drawable.bg_param_pill
            )
            if (!enabled) {
                binding.faceReticle.visibility = View.GONE
            }
        }

        updateAiTrackUI(settings.isAiTrackingEnabled)

        binding.btnAiTrack.setOnClickListener {
            val newState = streamer.toggleAiTracking()
            updateAiTrackUI(newState)
            showGestureFeedbackToast(if (newState) "AI tracking on" else "AI tracking off")
        }

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

        streamer.onAiFaceTrackingUpdate = { hasFace, bounds ->
            runOnUiThread {
                updateFaceReticle(hasFace, bounds)
            }
        }

        updateFlipPills()
        binding.btnFlipH.setOnClickListener {
            applyPreviewFlips(!settings.flipHorizontal, settings.flipVertical)
        }
        binding.btnFlipV.setOnClickListener {
            applyPreviewFlips(settings.flipHorizontal, !settings.flipVertical)
        }
    }

    private fun updateFlipPills() {
        val h = settings.flipHorizontal
        val v = settings.flipVertical
        binding.valPillFlipH.text = if (h) "ON" else "OFF"
        binding.valPillFlipV.text = if (v) "ON" else "OFF"
        binding.lblPillFlipH.setTextColor(
            ContextCompat.getColor(this, if (h) R.color.accent_green else R.color.text_secondary)
        )
        binding.lblPillFlipV.setTextColor(
            ContextCompat.getColor(this, if (v) R.color.accent_green else R.color.text_secondary)
        )
        binding.btnFlipH.setBackgroundResource(
            if (h) R.drawable.bg_param_pill_selected else R.drawable.bg_param_pill
        )
        binding.btnFlipV.setBackgroundResource(
            if (v) R.drawable.bg_param_pill_selected else R.drawable.bg_param_pill
        )
    }

    private fun applyPreviewFlips(horizontal: Boolean, vertical: Boolean) {
        settings.flipHorizontal = horizontal
        settings.flipVertical = vertical
        streamer.setPreviewFlips(horizontal, vertical)
        updateFlipPills()
        configureTransform(binding.cameraPreview.width, binding.cameraPreview.height)
        val label = buildString {
            if (horizontal) append("Flip H")
            if (horizontal && vertical) append(" + ")
            if (vertical) append("Flip V")
            if (!horizontal && !vertical) append("Flips off")
        }
        showGestureFeedbackToast(label)
    }

    private fun updateFaceReticle(hasFace: Boolean, bounds: RectF?) {
        if (!hasFace || bounds == null || !settings.isAiTrackingEnabled) {
            binding.faceReticle.visibility = View.GONE
            return
        }
        val preview = binding.cameraPreview
        val vw = preview.width.toFloat()
        val vh = preview.height.toFloat()
        if (vw <= 0f || vh <= 0f) return

        val rot = streamer.sensorOrientation
        fun mapPoint(nx: Float, ny: Float): Pair<Float, Float> {
            val (bx, by) = when (rot) {
                90 -> Pair(1f - ny, nx)
                180 -> Pair(1f - nx, 1f - ny)
                270 -> Pair(ny, 1f - nx)
                else -> Pair(nx, ny)
            }
            val pts = floatArrayOf(bx * vw, by * vh)
            val matrix = Matrix()
            preview.getTransform(matrix)
            matrix.mapPoints(pts)
            return Pair(preview.x + pts[0], preview.y + pts[1])
        }

        val (x1, y1) = mapPoint(bounds.left, bounds.top)
        val (x2, y2) = mapPoint(bounds.right, bounds.bottom)
        val left = min(x1, x2)
        val top = min(y1, y2)
        val rw = abs(x2 - x1).toInt().coerceAtLeast(48)
        val rh = abs(y2 - y1).toInt().coerceAtLeast(48)
        val lp = binding.faceReticle.layoutParams
        lp.width = rw
        lp.height = rh
        binding.faceReticle.layoutParams = lp
        binding.faceReticle.x = left
        binding.faceReticle.y = top
        binding.faceReticle.visibility = View.VISIBLE
    }

    private fun showCard(activeCard: CardView?, activePill: View?) {
        val allCards = listOf(
            binding.isoCard,
            binding.evCard,
            binding.zoomCard,
            binding.focusCard,
            binding.wbCard,
            binding.lensCard
        )
        val allPills = listOf(
            binding.btnIsoToggle,
            binding.btnEvToggle,
            binding.btnZoomToggle,
            binding.btnFocusToggle,
            binding.btnAwb,
            binding.btnSwitchLens
        )

        val isAlreadyOpen = activeCard?.visibility == View.VISIBLE

        allCards.forEach { card ->
            if (card.visibility == View.VISIBLE) {
                card.animate()
                    .alpha(0f)
                    .translationY(24f)
                    .setDuration(120)
                    .withEndAction { card.visibility = View.GONE }
                    .start()
            } else {
                card.visibility = View.GONE
            }
        }
        allPills.forEach {
            it.setBackgroundResource(R.drawable.bg_param_pill)
        }

        if (!isAlreadyOpen && activeCard != null && activePill != null) {
            activeCard.alpha = 0f
            activeCard.translationY = 24f
            activeCard.visibility = View.VISIBLE
            activeCard.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(160)
                .start()
            activePill.setBackgroundResource(R.drawable.bg_param_pill_selected)
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
        // --- 1. Lens Switch Overlay ---
        binding.btnSwitchLens.setOnClickListener {
            showCard(binding.lensCard, binding.btnSwitchLens)
        }

        fun applyLensSwitch(isBack: Boolean) {
            isBackCamera = isBack
            val title = if (isBack) "Rear Sensor (Wide)" else "Front Sensor"
            val pill = if (isBack) "REAR" else "FRONT"
            binding.txtLensValue.text = title
            binding.valPillLens.text = pill
            if (isBack) {
                binding.btnLensBack.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
                binding.btnLensFront.setTextColor(Color.WHITE)
            } else {
                binding.btnLensFront.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
                binding.btnLensBack.setTextColor(Color.WHITE)
            }
            streamer.switchLens(isBack)
        }
        binding.btnLensBack.setOnClickListener { applyLensSwitch(true) }
        binding.btnLensFront.setOnClickListener { applyLensSwitch(false) }

        // --- 2. Torch / Flash ---
        binding.btnTorch.setOnClickListener {
            val newState = !streamer.isTorchOn
            streamer.setTorch(newState)
            if (newState) {
                binding.btnTorch.setColorFilter(Color.parseColor("#FFD54F"))
            } else {
                binding.btnTorch.setColorFilter(Color.WHITE)
            }
        }

        // --- 3. White Balance Overlay ---
        binding.btnAwb.setOnClickListener {
            showCard(binding.wbCard, binding.btnAwb)
        }

        fun highlightWbPreset(activeBtn: Button?) {
            listOf(binding.btnWbAuto, binding.btnWbSun, binding.btnWbCloud, binding.btnWbFluor, binding.btnWbTungsten).forEach {
                it.setTextColor(Color.WHITE)
            }
            activeBtn?.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
        }

        fun applyManualKelvin(kelvin: Int, activeBtn: Button?) {
            val k = kelvin.coerceIn(2000, 8000)
            settings.lastKelvin = k
            settings.isManualWb = true
            streamer.setWhiteBalanceKelvin(k)
            binding.txtWbValue.text = "${k}K"
            binding.valPillWb.text = "${k}K"
            binding.seekWb.isEnabled = true
            binding.seekWb.progress = k - 2000
            highlightWbPreset(activeBtn)
        }

        fun applyAutoWb() {
            settings.isManualWb = false
            streamer.setAutoWhiteBalance()
            binding.txtWbValue.text = "AUTO (AWB)"
            binding.valPillWb.text = "AUTO"
            binding.seekWb.isEnabled = false
            highlightWbPreset(binding.btnWbAuto)
        }

        binding.btnWbAuto.setOnClickListener { applyAutoWb() }
        binding.btnWbSun.setOnClickListener { applyManualKelvin(5200, binding.btnWbSun) }
        binding.btnWbCloud.setOnClickListener { applyManualKelvin(6500, binding.btnWbCloud) }
        binding.btnWbFluor.setOnClickListener { applyManualKelvin(4000, binding.btnWbFluor) }
        binding.btnWbTungsten.setOnClickListener { applyManualKelvin(3200, binding.btnWbTungsten) }

        binding.seekWb.max = 6000
        binding.seekWb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val k = 2000 + progress
                binding.txtWbValue.text = "${k}K"
                binding.valPillWb.text = "${k}K"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val k = 2000 + (seekBar?.progress ?: 0)
                applyManualKelvin(k, null)
            }
        })

        if (settings.isManualWb) {
            applyManualKelvin(settings.lastKelvin, null)
        } else {
            applyAutoWb()
        }

        // --- 4. ISO Sensitivity (Smooth Non-Snap Slider + Direct Entry + Nudge) ---
        binding.btnIsoToggle.setOnClickListener {
            showCard(binding.isoCard, binding.btnIsoToggle)
        }

        fun updateIsoDisplay(iso: Int, isAuto: Boolean = false) {
            val text = if (isAuto) "AUTO" else "$iso"
            binding.txtIsoValue.text = text
            binding.valPillIso.text = text
            binding.lblPillIso.setTextColor(if (isAuto) ContextCompat.getColor(this, R.color.text_secondary) else ContextCompat.getColor(this, R.color.accent_green))
        }

        fun applyIsoChange(iso: Int) {
            val clamped = iso.coerceIn(streamer.minIso, streamer.maxIso)
            streamer.setIso(clamped)
            val frac = if (streamer.maxIso > streamer.minIso) (clamped - streamer.minIso).toFloat() / (streamer.maxIso - streamer.minIso).toFloat() else 0f
            binding.seekIso.progress = (frac * 10000).toInt()
            updateIsoDisplay(clamped, false)
            binding.btnIsoAuto.text = "MANUAL"
            binding.btnIsoAuto.setBackgroundColor(ContextCompat.getColor(this, R.color.exposure_amber))
            binding.btnIsoAuto.setTextColor(Color.BLACK)
        }

        binding.seekIso.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val frac = progress.toFloat() / 10000f
                    val iso = (streamer.minIso + frac * (streamer.maxIso - streamer.minIso)).roundToInt()
                    streamer.setIso(iso)
                    updateIsoDisplay(iso, false)
                    binding.btnIsoAuto.text = "MANUAL"
                    binding.btnIsoAuto.setBackgroundColor(ContextCompat.getColor(this@DroidCamActivity, R.color.exposure_amber))
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
                binding.btnIsoAuto.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_green))
                binding.btnIsoAuto.setTextColor(Color.BLACK)
                updateIsoDisplay(streamer.actualSensorIso, true)
            } else {
                binding.btnIsoAuto.text = "MANUAL"
                binding.btnIsoAuto.setBackgroundColor(ContextCompat.getColor(this, R.color.exposure_amber))
                binding.btnIsoAuto.setTextColor(Color.BLACK)
                streamer.setIso(streamer.currentIso)
                updateIsoDisplay(streamer.currentIso, false)
            }
        }

        binding.btnIsoMinus.setOnClickListener {
            applyIsoChange(streamer.currentIso - 100)
        }

        binding.btnIsoPlus.setOnClickListener {
            applyIsoChange(streamer.currentIso + 100)
        }

        val openIsoDialog = {
            showDirectNumericEditDialog(
                title = "Direct ISO Entry",
                currentValueStr = "${streamer.currentIso}",
                unitLabel = "ISO",
                minVal = streamer.minIso.toFloat(),
                maxVal = streamer.maxIso.toFloat(),
                isInteger = true
            ) { value ->
                applyIsoChange(value.roundToInt())
            }
        }
        binding.txtIsoValue.setOnClickListener { openIsoDialog() }
        binding.btnIsoDirect.setOnClickListener { openIsoDialog() }

        // --- 5. EV Compensation (Centered Zero Scale + Nudge + Direct Entry) ---
        binding.btnEvToggle.setOnClickListener {
            showCard(binding.evCard, binding.btnEvToggle)
        }

        fun updateEvDisplay(evReal: Float) {
            val str = String.format("%+.1f", evReal)
            binding.txtEvValue.text = str
            binding.valPillEv.text = str
        }

        fun applyEvSteps(steps: Int) {
            val clamped = steps.coerceIn(streamer.minExposureCompensation, streamer.maxExposureCompensation)
            streamer.setExposureCompensation(clamped)
            val evReal = clamped * streamer.exposureCompensationStep
            val span = streamer.maxExposureCompensation - streamer.minExposureCompensation
            val frac = if (span > 0) (clamped - streamer.minExposureCompensation).toFloat() / span.toFloat() else 0.5f
            binding.seekEv.progress = (frac * 10000).toInt()
            updateEvDisplay(evReal)
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
                    updateEvDisplay(evReal)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.btnEvMinus.setOnClickListener {
            applyEvSteps(streamer.currentExposureCompensation - 1)
        }

        binding.btnEvReset.setOnClickListener {
            applyEvSteps(0)
        }

        binding.btnEvPlus.setOnClickListener {
            applyEvSteps(streamer.currentExposureCompensation + 1)
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

        val openEvDialog = {
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
                val evSteps = (value / step).roundToInt()
                applyEvSteps(evSteps)
            }
        }
        binding.txtEvValue.setOnClickListener { openEvDialog() }
        binding.btnEvDirect.setOnClickListener { openEvDialog() }

        // --- 6. Zoom (Smooth Continuous + Presets + Direct Entry) ---
        binding.btnZoomToggle.setOnClickListener {
            showCard(binding.zoomCard, binding.btnZoomToggle)
        }

        fun updateZoomDisplay(z: Float) {
            val str = String.format("%.1f×", z)
            binding.txtZoomValue.text = str
            binding.valPillZoom.text = str
        }

        fun applyZoom(z: Float) {
            val clamped = z.coerceIn(streamer.minZoomFactor, streamer.maxZoomFactor)
            streamer.setZoom(clamped)
            val span = streamer.maxZoomFactor - streamer.minZoomFactor
            val frac = if (span > 0f) (clamped - streamer.minZoomFactor) / span else 0f
            binding.seekZoom.progress = (frac * 10000).toInt()
            updateZoomDisplay(clamped)
        }

        binding.seekZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val minZ = streamer.minZoomFactor
                    val maxZ = streamer.maxZoomFactor
                    val frac = progress.toFloat() / 10000f
                    val z = minZ + frac * (maxZ - minZ)
                    streamer.setZoom(z)
                    updateZoomDisplay(z)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.btnZoom05x.setOnClickListener { applyZoom(0.5f) }
        binding.btnZoom1x.setOnClickListener { applyZoom(1.0f) }
        binding.btnZoom2x.setOnClickListener { applyZoom(2.0f) }
        binding.btnZoom3x.setOnClickListener { applyZoom(3.0f) }
        binding.btnZoom5x.setOnClickListener { applyZoom(5.0f) }
        binding.btnZoom10x.setOnClickListener { applyZoom(10.0f) }

        val openZoomDialog = {
            showDirectNumericEditDialog(
                title = "Direct Zoom Entry",
                currentValueStr = String.format("%.2f", streamer.currentZoom),
                unitLabel = "×",
                minVal = streamer.minZoomFactor,
                maxVal = streamer.maxZoomFactor,
                isInteger = false
            ) { value -> applyZoom(value) }
        }
        binding.txtZoomValue.setOnClickListener { openZoomDialog() }

        // --- 7. Focus (Distance Puller + Modes AF-S / AF-C / MF) ---
        binding.btnFocusToggle.setOnClickListener {
            showCard(binding.focusCard, binding.btnFocusToggle)
        }

        fun updateFocusDisplay(text: String) {
            binding.txtFocusValue.text = text
            binding.valPillFocus.text = text
        }

        fun setFocusSegment(activeBtn: Button) {
            listOf(binding.btnFocusAfs, binding.btnFocusAuto, binding.btnFocusMf).forEach {
                it.setBackgroundColor(Color.parseColor("#222430"))
                it.setTextColor(Color.WHITE)
            }
            activeBtn.setBackgroundColor(Color.parseColor("#00E676"))
            activeBtn.setTextColor(Color.BLACK)
        }

        binding.btnFocusAfs.setOnClickListener {
            streamer.setAutoAf(CameraMetadata.CONTROL_AF_MODE_AUTO)
            setFocusSegment(binding.btnFocusAfs)
            updateFocusDisplay("AF-S")
        }

        binding.btnFocusAuto.setOnClickListener {
            streamer.setAutoAf(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            setFocusSegment(binding.btnFocusAuto)
            updateFocusDisplay("AF-C")
        }

        binding.btnFocusMf.setOnClickListener {
            setFocusSegment(binding.btnFocusMf)
            val dist = binding.seekFocus.progress.toFloat() / 10000f * streamer.maxFocusDistance
            streamer.setFocusDistance(dist)
            updateFocusDisplay(String.format("%.2f dpt", dist))
        }

        binding.seekFocus.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val frac = progress.toFloat() / 10000f
                    val dist = frac * streamer.maxFocusDistance
                    streamer.setFocusDistance(dist)
                    setFocusSegment(binding.btnFocusMf)
                    updateFocusDisplay(String.format("%.2f dpt", dist))
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.btnFocusDirect.setOnClickListener {
            showDirectNumericEditDialog(
                title = "Direct Focus Distance",
                currentValueStr = String.format("%.2f", streamer.currentFocusDistance),
                unitLabel = "diopters (0 = inf)",
                minVal = 0.0f,
                maxVal = streamer.maxFocusDistance,
                isInteger = false
            ) { value ->
                streamer.setFocusDistance(value)
                val frac = if (streamer.maxFocusDistance > 0f) value / streamer.maxFocusDistance else 0f
                binding.seekFocus.progress = (frac * 10000).toInt()
                setFocusSegment(binding.btnFocusMf)
                updateFocusDisplay(String.format("%.2f dpt", value))
            }
        }
    }

    private fun setupTouchFocus() {
        binding.cameraPreview.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val anyCardOpen = listOf(
                    binding.isoCard,
                    binding.evCard,
                    binding.zoomCard,
                    binding.focusCard,
                    binding.wbCard,
                    binding.lensCard
                ).any { it.visibility == View.VISIBLE }

                if (anyCardOpen) {
                    showCard(null, null)
                    return@setOnTouchListener true
                }

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
        showCard(null, null)
        val dialog = BottomSheetDialog(this)
        val sheetBinding = DialogDroidcamSettingsBinding.inflate(layoutInflater)
        dialog.setContentView(sheetBinding.root)

        val selectedChip = ContextCompat.getColor(this, R.color.accent_green)
        val idleChip = ContextCompat.getColor(this, R.color.surface_control)
        val selectedChipText = ContextCompat.getColor(this, R.color.black)
        val idleChipText = ContextCompat.getColor(this, R.color.text_primary)

        fun styleChip(btn: Button, selected: Boolean) {
            btn.setBackgroundResource(if (selected) R.drawable.bg_param_pill_selected else R.drawable.bg_param_pill)
            btn.backgroundTintList = null
            btn.setTextColor(if (selected) ContextCompat.getColor(this, R.color.accent_green) else idleChipText)
        }

        fun switchTab(activeBtn: Button, activeSection: View) {
            sheetBinding.sectionVideo.visibility = View.GONE
            sheetBinding.sectionCamera.visibility = View.GONE
            sheetBinding.sectionStream.visibility = View.GONE

            listOf(sheetBinding.tabVideo, sheetBinding.tabCamera, sheetBinding.tabStream).forEach {
                it.setBackgroundResource(R.drawable.bg_param_pill)
                it.backgroundTintList = android.content.res.ColorStateList.valueOf(idleChip)
                it.setTextColor(idleChipText)
            }

            activeSection.visibility = View.VISIBLE
            activeBtn.setBackgroundResource(R.drawable.bg_param_pill_selected)
            activeBtn.backgroundTintList = android.content.res.ColorStateList.valueOf(selectedChip)
            activeBtn.setTextColor(selectedChipText)
        }

        sheetBinding.tabVideo.setOnClickListener { switchTab(sheetBinding.tabVideo, sheetBinding.sectionVideo) }
        sheetBinding.tabCamera.setOnClickListener { switchTab(sheetBinding.tabCamera, sheetBinding.sectionCamera) }
        sheetBinding.tabStream.setOnClickListener { switchTab(sheetBinding.tabStream, sheetBinding.sectionStream) }
        switchTab(sheetBinding.tabVideo, sheetBinding.sectionVideo)

        sheetBinding.btnCloseSheet.setOnClickListener {
            dialog.dismiss()
        }

        // Resolutions
        val curW = settings.targetResolutionWidth
        val curH = settings.targetResolutionHeight
        sheetBinding.txtCurrentResolution.text = settings.getResolutionLabel()

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
            sheetBinding.txtCurrentResolution.text = settings.getResolutionLabel()

            resMap.forEach { (btn, res) ->
                styleChip(btn, res.first == selectedW && res.second == selectedH)
            }

            if (droidCamServer.isStreaming) {
                streamer.stopStream()
                streamer.startStream(streamer.currentFormat, selectedW, selectedH, settings.targetFps, isBackCamera)
            }
            updateConnectionPill()
        }
        updateResolutionUI(curW, curH)

        resMap.forEach { (btn, res) ->
            btn.setOnClickListener { updateResolutionUI(res.first, res.second) }
        }

        sheetBinding.btnProbeResolutions.setOnClickListener {
            dialog.dismiss()
            showCameraProbeDialog()
        }

        // Bitrates
        val currentKbps = settings.targetBitrateKbps
        sheetBinding.txtCurrentBitrate.text = "${formatBitrateKbps(currentKbps)} CBR"

        val bitrateMap: Map<Button, Int> = mapOf(
            sheetBinding.btnBitrate25 to 2500,
            sheetBinding.btnBitrate4 to 4000,
            sheetBinding.btnBitrate8 to 8000,
            sheetBinding.btnBitrate12 to 12000,
            sheetBinding.btnBitrate20 to 20000,
            sheetBinding.btnBitrate40 to 40000
        )

        fun updateBitrateUI(selectedKbps: Int) {
            settings.targetBitrateKbps = selectedKbps
            sheetBinding.txtCurrentBitrate.text = "${formatBitrateKbps(selectedKbps)} CBR"
            bitrateMap.forEach { (btn, kbps) ->
                styleChip(btn, kbps == selectedKbps)
            }
            updateConnectionPill()
        }
        updateBitrateUI(currentKbps)

        bitrateMap.forEach { (btn, kbps) ->
            btn.setOnClickListener { updateBitrateUI(kbps) }
        }

        // Target FPS
        val currentFps = settings.targetFps
        sheetBinding.txtCurrentFps.text = "$currentFps fps"

        val fpsMap = mapOf(
            sheetBinding.btnFps24 to 24,
            sheetBinding.btnFps30 to 30,
            sheetBinding.btnFps48 to 48,
            sheetBinding.btnFps60 to 60
        )

        fun updateFpsUI(selectedFps: Int) {
            settings.targetFps = selectedFps
            sheetBinding.txtCurrentFps.text = "$selectedFps fps"
            fpsMap.forEach { (btn, fps) ->
                styleChip(btn, fps == selectedFps)
            }
            updateConnectionPill()
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
                styleChip(btn, m.equals(mode, ignoreCase = true))
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

        sheetBinding.switchFlipH.isChecked = settings.flipHorizontal
        sheetBinding.switchFlipH.setOnCheckedChangeListener { _, isChecked ->
            applyPreviewFlips(isChecked, settings.flipVertical)
        }

        sheetBinding.switchFlipV.isChecked = settings.flipVertical
        sheetBinding.switchFlipV.setOnCheckedChangeListener { _, isChecked ->
            applyPreviewFlips(settings.flipHorizontal, isChecked)
        }

        sheetBinding.switchTally.isChecked = settings.showTallyIndicator
        sheetBinding.switchTally.setOnCheckedChangeListener { _, isChecked ->
            settings.showTallyIndicator = isChecked
            binding.txtTally.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        sheetBinding.switchAiTracking.isChecked = settings.isAiTrackingEnabled
        sheetBinding.switchAiTracking.setOnCheckedChangeListener { _, isChecked ->
            streamer.setAiTrackingEnabled(isChecked)
        }

        sheetBinding.switchGestures.isChecked = settings.isGesturesEnabled
        sheetBinding.switchGestures.setOnCheckedChangeListener { _, isChecked ->
            streamer.setGesturesEnabled(isChecked)
        }

        sheetBinding.btnCloseSettings.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showCameraProbeDialog() {
        showCard(null, null)
        val dialog = BottomSheetDialog(this)
        val probeBinding = DialogSensorProbeBinding.inflate(layoutInflater)
        dialog.setContentView(probeBinding.root)

        // FAANG-grade probe: real HAL values, not hardcoded
        val report = com.darusc.vcamdroid.capabilities.CameraCapabilityProbe.probeSync(this)
        com.darusc.vcamdroid.capabilities.CapabilityRepository.getInstance().refreshSync(this)

        probeBinding.txtDeviceInfo.text = "${report.manufacturer} ${report.model} • ${report.cameras.size} camera IDs • SDK ${report.sdk}"

        var selectedFacing: com.darusc.vcamdroid.capabilities.Facing? = com.darusc.vcamdroid.capabilities.Facing.BACK
        var showAll = false
        var currentNodes: List<com.darusc.vcamdroid.capabilities.CameraNode> = report.backCameras

        fun styleTab(active: android.widget.Button, others: List<android.widget.Button>) {
            active.setBackgroundColor(Color.parseColor("#00E676"))
            active.setTextColor(Color.BLACK)
            others.forEach {
                it.setBackgroundColor(Color.parseColor("#222430"))
                it.setTextColor(Color.WHITE)
            }
        }

        fun renderCapabilities() {
            probeBinding.resolutionsContainer.removeAllViews()

            val nodesToShow = when {
                showAll -> report.cameras
                selectedFacing != null -> report.cameras.filter { it.facing == selectedFacing }
                else -> report.cameras
            }

            currentNodes = nodesToShow

            if (nodesToShow.isEmpty()) {
                probeBinding.txtCameraIdHeader.text = "No cameras found"
                probeBinding.txtResolutionCount.text = "0 modes"
                return
            }

            // Show primary node details in header card
            val primary = nodesToShow.firstOrNull()
            if (primary != null) {
                probeBinding.txtCameraIdHeader.text = "${primary.displayName} - ${primary.id} - ${primary.hardwareLevel}"
                probeBinding.txtHardwareLevel.text = "HW Level: ${primary.hardwareLevel} | Logical: ${primary.features.isLogical} | Physical: ${primary.features.physicalIds} | Facing: ${primary.facing}"
                probeBinding.txtSensorInfo.text = "Sensor: Active ${primary.sensor.activeArray.width()}x${primary.sensor.activeArray.height()} @ ${primary.sensor.activeArray} | Pixel ${primary.sensor.pixelArraySize} | Orient ${primary.sensor.orientation}° | Physical ${primary.sensor.physicalSize}"
                probeBinding.txtLensInfo.text = "Lens: f=${primary.lens.focalLengths.joinToString(",")}mm | MinFocus ${primary.lens.minFocusDistance?.let { String.format("%.2f diopters (%.0fcm)", it, 100f/it) } ?: "fixed focus"} | Calib ${primary.lens.focusCalibration}"
                probeBinding.txtControlsInfo.text = "Controls: ISO ${primary.controls.isoRange ?: "N/A"} | EV ${primary.controls.evRange} step ${primary.controls.evStep} | ZoomRatio ${primary.controls.zoomRatioRange ?: "N/A"} | MaxDigitalZoom ${primary.controls.maxDigitalZoom}x | ManualIso ${primary.supportsManualIso()} MF ${primary.supportsManualFocus()}"
                probeBinding.txtExposureInfo.text = "Exposure: ${primary.controls.exposureTimeRange?.let { "${it.lower/1_000_000}ms-${it.upper/1_000_000}ms" } ?: "N/A"} | FPS Ranges: ${primary.controls.fpsRanges.take(4).joinToString()} | Flash ${primary.features.hasFlash}"
                probeBinding.txtFeaturesInfo.text = "Features: AF modes ${primary.features.afModes} | AWB ${primary.features.awbModes} | AE ${primary.features.aeModes} | OIS ${primary.features.stabilizationModes} | Caps ${primary.features.capabilities.take(5)}"
            }

            // Aggregate all profiles for selected facing
            val allProfiles = nodesToShow.flatMap { it.streams.allProfiles }.distinctBy { "${it.width}x${it.height}" }.sortedByDescending { it.width * it.height }
            probeBinding.txtResolutionCount.text = "${allProfiles.size} modes"

            for (profile in allProfiles) {
                val itemBinding = ItemSensorResolutionBinding.inflate(layoutInflater, probeBinding.resolutionsContainer, false)
                itemBinding.txtResolution.text = "${profile.width} × ${profile.height}"
                itemBinding.txtAspectRatio.text = profile.aspectRatioLabel

                val fpsText = if (profile.maxFps != null) "max ${profile.maxFps}fps" else "fps ?"
                val mpText = String.format("%.1fMP", profile.megapixels)
                val durText = profile.minFrameDurationNs?.let { "${it/1_000_000}ms" } ?: "?"
                itemBinding.txtFpsInfo.text = "$fpsText • $mpText • $durText minDuration ${if (profile.isHighSpeed) "[HS]" else ""}"

                itemBinding.txtFormatBadge.text = profile.formats.take(3).joinToString("+")

                itemBinding.root.setOnClickListener {
                    settings.targetResolutionWidth = profile.width
                    settings.targetResolutionHeight = profile.height
                    adjustPreviewAspectRatio(profile.width, profile.height)
                    binding.cameraPreview.surfaceTexture?.setDefaultBufferSize(profile.width, profile.height)
                    if (droidCamServer.isStreaming) {
                        streamer.stopStream()
                        streamer.startStream(streamer.currentFormat, profile.width, profile.height, settings.targetFps, isBackCamera)
                    }
                    updateConnectionPill()
                    Toast.makeText(this, "Target: ${profile.width}×${profile.height} max ${profile.maxFps}fps", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
                probeBinding.resolutionsContainer.addView(itemBinding.root)
            }

            // If showing all, also show per-camera breakdown
            if (showAll) {
                for (node in nodesToShow) {
                    val header = TextView(this).apply {
                        text = "── ${node.displayName} (${node.streams.allProfiles.size} modes) ──"
                        setTextColor(Color.parseColor("#9E9EA7"))
                        textSize = 11f
                        setPadding(0, 24, 0, 8)
                        typeface = android.graphics.Typeface.MONOSPACE
                    }
                    probeBinding.resolutionsContainer.addView(header)
                    node.streams.allProfiles.take(5).forEach { p ->
                        val tv = TextView(this).apply {
                            text = "  ${p.width}x${p.height} ${p.aspectRatioLabel} max ${p.maxFps}fps"
                            setTextColor(Color.parseColor("#666672"))
                            textSize = 10f
                            typeface = android.graphics.Typeface.MONOSPACE
                            setPadding(0, 2, 0, 2)
                        }
                        probeBinding.resolutionsContainer.addView(tv)
                    }
                }
            }
        }

        probeBinding.btnTabRear.setOnClickListener {
            selectedFacing = com.darusc.vcamdroid.capabilities.Facing.BACK
            showAll = false
            styleTab(probeBinding.btnTabRear, listOf(probeBinding.btnTabFront, probeBinding.btnTabAll))
            renderCapabilities()
        }
        probeBinding.btnTabFront.setOnClickListener {
            selectedFacing = com.darusc.vcamdroid.capabilities.Facing.FRONT
            showAll = false
            styleTab(probeBinding.btnTabFront, listOf(probeBinding.btnTabRear, probeBinding.btnTabAll))
            renderCapabilities()
        }
        probeBinding.btnTabAll.setOnClickListener {
            showAll = true
            selectedFacing = null
            styleTab(probeBinding.btnTabAll, listOf(probeBinding.btnTabRear, probeBinding.btnTabFront))
            renderCapabilities()
        }
        probeBinding.btnProbeBack.setOnClickListener { dialog.dismiss() }

        probeBinding.btnCopyClipboard.setOnClickListener {
            val sb = StringBuilder("=== VCamdroid Sensor Probe (Real HAL) ===\n")
            sb.append("Device: ${report.manufacturer} ${report.model} SDK ${report.sdk}\n")
            sb.append("Cameras: ${report.cameras.size}\n\n")
            report.cameras.forEach { cam ->
                sb.append("--- ${cam.displayName} ---\n")
                sb.append("HW: ${cam.hardwareLevel} Logical: ${cam.features.isLogical} Physical: ${cam.features.physicalIds}\n")
                sb.append("Sensor Active: ${cam.sensor.activeArray} Pixel: ${cam.sensor.pixelArraySize} Orient: ${cam.sensor.orientation}\n")
                sb.append("Resolutions: ${cam.streams.allProfiles.joinToString { "${it.width}x${it.height}@${it.maxFps}fps" }}\n\n")
            }
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("VCamdroid Probe", sb.toString()))
            Toast.makeText(this, "Resolutions copied", Toast.LENGTH_SHORT).show()
        }

        probeBinding.btnCopyFullReport.setOnClickListener {
            val full = report.toHumanReadable()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("VCamdroid Full HAL Report", full))
            Toast.makeText(this, "Full HAL report copied (${full.length} chars)", Toast.LENGTH_LONG).show()
        }

        // Initial render
        styleTab(probeBinding.btnTabRear, listOf(probeBinding.btnTabFront, probeBinding.btnTabAll))
        renderCapabilities()
        dialog.show()
    }

    private fun showConnectionDetailsDialog
        renderResolutions(true)
        dialog.show()
    }

    private fun showConnectionDetailsDialog(preferredMode: String? = null) {
        showCard(null, null)
        val dialog = BottomSheetDialog(this)
        val connBinding = DialogConnectionDetailsBinding.inflate(layoutInflater)
        dialog.setContentView(connBinding.root)

        val localIp = getLocalIpAddress()
        val wifiIpStr = if (localIp != null) "http://$localIp:${settings.port}/video" else "Wi-Fi Disconnected"
        connBinding.txtWifiIpDisplay.text = wifiIpStr

        if (localIp != null) {
            connBinding.badgeWifiStatus.text = "ACTIVE"
            connBinding.badgeWifiStatus.setTextColor(Color.parseColor("#00E676"))
        } else {
            connBinding.badgeWifiStatus.text = "OFFLINE"
            connBinding.badgeWifiStatus.setTextColor(Color.parseColor("#787C96"))
        }

        val isUsbPlugged = isUsbConnection || hasUsbHardwareConnection()
        connBinding.badgeUsbStatus.text = if (isUsbPlugged) "CONNECTED" else "READY"
        connBinding.badgeUsbStatus.setTextColor(if (isUsbPlugged) Color.parseColor("#00E676") else Color.parseColor("#787C96"))

        val adbCmd = "adb forward tcp:${settings.port} tcp:${settings.port}"
        connBinding.txtAdbCommandDisplay.text = adbCmd

        connBinding.btnCopyWifiUrl.setOnClickListener {
            if (localIp != null) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Wi-Fi URL", wifiIpStr))
                Toast.makeText(this, "Copied: $wifiIpStr", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Please connect phone to Wi-Fi", Toast.LENGTH_SHORT).show()
            }
        }

        connBinding.btnCopyAdbCommand.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("ADB Command", adbCmd))
            Toast.makeText(this, "Copied: $adbCmd", Toast.LENGTH_SHORT).show()
        }

        connBinding.btnCloseConnectionDialog.setOnClickListener {
            dialog.dismiss()
        }

        if (preferredMode == "WIFI") {
            connBinding.cardWifiEndpoint.setBackgroundResource(R.drawable.bg_param_pill_selected)
        } else if (preferredMode == "USB") {
            connBinding.cardUsbEndpoint.setBackgroundResource(R.drawable.bg_param_pill_selected)
        }

        dialog.show()
    }

    private fun hasUsbHardwareConnection(): Boolean {
        val intent = applicationContext.registerReceiver(
            null,
            android.content.IntentFilter("android.hardware.usb.action.USB_STATE")
        )
        return intent?.getBooleanExtra("connected", false) == true
    }

    private fun toggleDimMode(enable: Boolean) {
        isDimMode = enable
        val lp = window.attributes
        if (enable) {
            binding.dimOverlay.visibility = View.VISIBLE
            lp.screenBrightness = 0.01f // AMOLED minimum brightness
            val mode = if (isUsbConnection) "USB" else "Wi-Fi"
            val mbps = formatBitrateKbps(settings.targetBitrateKbps)
            binding.dimInstructions.text = "Encoding in background • $mode\n${settings.targetResolutionWidth}×${settings.targetResolutionHeight} • ${streamer.currentFormat.uppercase()} • ${settings.targetFps} fps • $mbps"
        } else {
            binding.dimOverlay.visibility = View.GONE
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        window.attributes = lp
    }

    private var streamStartTimeMs: Long = 0L
    private val durationHandler = Handler(Looper.getMainLooper())
    private val durationRunnable = object : Runnable {
        override fun run() {
            if (droidCamServer.isStreaming) {
                val elapsedSec = (SystemClock.elapsedRealtime() - streamStartTimeMs) / 1000
                val hrs = elapsedSec / 3600
                val mins = (elapsedSec % 3600) / 60
                val secs = elapsedSec % 60
                val timeStr = String.format("%02d:%02d:%02d", hrs, mins, secs)
                binding.dimDuration.text = timeStr
                if (settings.showTallyIndicator) {
                    binding.txtTally.text = "● ON AIR  $timeStr"
                }
                durationHandler.postDelayed(this, 1000)
            }
        }
    }

    private fun syncHudToCapabilities() {
        val minIso = streamer.minIso
        val maxIso = streamer.maxIso
        val isoTicks = listOf(
            binding.tickIso0, binding.tickIso1, binding.tickIso2,
            binding.tickIso3, binding.tickIso4, binding.tickIso5, binding.tickIso6
        )
        isoTicks.forEachIndexed { index, tick ->
            val t = index / (isoTicks.size - 1).toFloat()
            val iso = (minIso + t * (maxIso - minIso)).roundToInt()
            tick.text = iso.toString()
        }

        binding.btnZoom05x.visibility = if (streamer.minZoomFactor < 0.75f) View.VISIBLE else View.GONE

        if (!streamer.isManualIso) {
            binding.valPillIso.text = "AUTO"
            binding.txtIsoValue.text = "AUTO"
        }

        updateConnectionPill()
    }

    private fun updateConnectionPill() {
        val localIp = getLocalIpAddress()
        val bitrate = formatBitrateKbps(settings.targetBitrateKbps)
        if (droidCamServer.isStreaming) {
            binding.txtConnectionPill.text = if (isUsbConnection) "USB · live" else "Wi-Fi · live"
            binding.txtSubStatus.text =
                "${settings.targetResolutionWidth}×${settings.targetResolutionHeight} • ${streamer.currentFormat.uppercase()} • ${settings.targetFps} fps • $bitrate"
        } else {
            binding.txtConnectionPill.text = "Waiting for OBS"
            binding.txtSubStatus.text = if (localIp != null) {
                "$localIp:${settings.port}"
            } else {
                "Tap for USB setup · port ${settings.port}"
            }
        }
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

    private var previewSurface: Surface? = null

    private fun adjustPreviewAspectRatio(streamWidth: Int, streamHeight: Int) {
        if (streamWidth <= 0 || streamHeight <= 0) return
        binding.cameraPreview.setAspectRatio(streamWidth, streamHeight)
        binding.cameraPreview.post {
            configureTransform(binding.cameraPreview.width, binding.cameraPreview.height)
        }
    }

    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        if (viewWidth <= 0 || viewHeight <= 0) return
        val bufferW = settings.targetResolutionWidth
        val bufferH = settings.targetResolutionHeight
        if (bufferW <= 0 || bufferH <= 0) return

        val sensorRot = streamer.sensorOrientation
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val swapped = sensorRot == 90 || sensorRot == 270
        val bufferRect = if (swapped) {
            RectF(0f, 0f, bufferH.toFloat(), bufferW.toFloat())
        } else {
            RectF(0f, 0f, bufferW.toFloat(), bufferH.toFloat())
        }
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()
        bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
        matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)

        val scale = if (swapped) {
            max(viewHeight.toFloat() / bufferH, viewWidth.toFloat() / bufferW)
        } else {
            max(viewWidth.toFloat() / bufferW, viewHeight.toFloat() / bufferH)
        }
        matrix.postScale(scale, scale, centerX, centerY)
        if (sensorRot != 0) {
            matrix.postRotate(sensorRot.toFloat(), centerX, centerY)
        }

        val bothFlips = settings.flipHorizontal && settings.flipVertical
        if (!(bothFlips && streamer.rotateAndCrop180Active)) {
            if (settings.flipHorizontal) matrix.postScale(-1f, 1f, centerX, centerY)
            if (settings.flipVertical) matrix.postScale(1f, -1f, centerX, centerY)
        }

        binding.cameraPreview.setTransform(matrix)
    }

    // --- TextureView.SurfaceTextureListener ---

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        surfaceTexture.setDefaultBufferSize(settings.targetResolutionWidth, settings.targetResolutionHeight)
        adjustPreviewAspectRatio(settings.targetResolutionWidth, settings.targetResolutionHeight)
        configureTransform(width, height)
        val surface = Surface(surfaceTexture)
        previewSurface = surface
        streamer.onScreenOn(surface)
    }

    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        configureTransform(width, height)
    }

    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        previewSurface?.release()
        previewSurface = null
        streamer.onScreenOff()
        return true
    }

    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {}

    // --- DroidCamServer.Listener ---

    private var isUsbConnection = false

    override fun onVideoStreamStarted(format: String, width: Int, height: Int) {
        runOnUiThread {
            adjustPreviewAspectRatio(width, height)
            updateTallyBadge("program")
            streamStartTimeMs = SystemClock.elapsedRealtime()
            durationHandler.post(durationRunnable)
            StreamingService.updateStatus(this, "On air in OBS")
            updateConnectionPill()
        }
        streamer.startStream(format, width, height, settings.targetFps, isBackCamera)
    }

    override fun onVideoStreamStopped() {
        runOnUiThread {
            updateConnectionPill()
            updateTallyBadge("idle")
            durationHandler.removeCallbacks(durationRunnable)
            binding.dimDuration.text = "00:00:00"
            StreamingService.updateStatus(this, "Waiting for OBS")
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
            updateConnectionPill()
        }
    }

    override fun onClientDisconnected() {
        isUsbConnection = false
        runOnUiThread {
            updateConnectionPill()
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
                binding.txtTally.text = "● ON AIR"
                binding.txtTally.setBackgroundResource(R.drawable.bg_tally_on_air)
                binding.txtTally.setTextColor(Color.WHITE)
                binding.dimTallyStatus.text = "ON AIR"
                binding.dimTallyStatus.setTextColor(ContextCompat.getColor(this, R.color.broadcast_red))
                binding.dimTallyDot.visibility = View.VISIBLE
                binding.btnCenterAction.setBackgroundResource(R.drawable.bg_action_circle_live)
            }
            "preview" -> {
                binding.txtTally.text = "PREVIEW"
                binding.txtTally.setBackgroundResource(R.drawable.bg_tally_standby)
                binding.txtTally.setTextColor(ContextCompat.getColor(this, R.color.exposure_amber))
                binding.dimTallyStatus.text = "PREVIEW"
                binding.dimTallyStatus.setTextColor(ContextCompat.getColor(this, R.color.exposure_amber))
                binding.dimTallyDot.visibility = View.GONE
                binding.btnCenterAction.setBackgroundResource(R.drawable.bg_action_circle)
            }
            else -> {
                binding.txtTally.text = "STANDBY"
                binding.txtTally.setBackgroundResource(R.drawable.bg_tally_standby)
                binding.txtTally.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                binding.dimTallyStatus.text = "STANDBY"
                binding.dimTallyStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                binding.dimTallyDot.visibility = View.GONE
                binding.btnCenterAction.setBackgroundResource(R.drawable.bg_action_circle)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        durationHandler.removeCallbacks(durationRunnable)
        if (isFinishing) {
            mdnsAdvertiser.stopAdvertising()
            droidCamServer.stop()
            streamer.release()
            StreamingService.stopService(this)
        }
    }
}
