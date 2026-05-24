package `fun`.kirari.hanako.overlay

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ServiceLifecycleDispatcher
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import `fun`.kirari.hanako.R
import `fun`.kirari.hanako.capture.MediaProjectionForegroundService
import `fun`.kirari.hanako.ui.theme.HanakoTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class OverlayService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val dispatcher = ServiceLifecycleDispatcher(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val serviceViewModelStore = ViewModelStore()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override val lifecycle: Lifecycle
        get() = dispatcher.lifecycle

    override val savedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override val viewModelStore: ViewModelStore
        get() = serviceViewModelStore

    private lateinit var windowManager: WindowManager
    private var bubbleView: ImageView? = null
    private var panelView: FrameLayout? = null
    private var panelContentView: ComposeView? = null
    private var panelHandleView: FrameLayout? = null
    private var stableTestPanelView: FrameLayout? = null
    private var stableTestHandleView: FrameLayout? = null
    private lateinit var overlayViewModel: OverlayViewModel
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var panelHandleParams: WindowManager.LayoutParams? = null
    private var stableTestPanelParams: WindowManager.LayoutParams? = null
    private var stableTestHandleParams: WindowManager.LayoutParams? = null
    private var panelScreenHeightPx: Int = 0
    private var panelHeightPx: Int = 0
    private var panelDockHeightPx: Int = 0
    private var panelCurrentHeightPx: Int = 0
    private var panelHandleHeightPx: Int = 0
    private var panelHandleWidthPx: Int = 0
    private var panelAnimationJob: Job? = null
    private var panelClosing = false

    override fun onCreate() {
        dispatcher.onServicePreSuperOnCreate()
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        runCatching {
            overlayViewModel = ViewModelProvider(
                this,
                OverlayViewModel.factory(applicationContext)
            )[OverlayViewModel::class.java]
            showBubble()
            observeSheetState()
        }.onFailure {
            Log.e("OverlayService", "Overlay initialization failed", it)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        dispatcher.onServicePreSuperOnStart()
        when (intent?.action) {
            ACTION_STOP -> {
                dismissPanel()
                dismissStableTestPanel()
                stopSelf()
            }

            ACTION_TEST_SHEET_STABLE -> showStableTestPanel()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        dismissPanel()
        dismissStableTestPanel()
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView = null
        serviceViewModelStore.clear()
        serviceScope.cancel()
        super.onDestroy()
        dispatcher.onServicePreSuperOnDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun observeSheetState() {
        serviceScope.launch {
            overlayViewModel.uiState.collect { state ->
                if (state.sheetVisible) {
                    showOrUpdatePanel(state.sheetMode)
                } else {
                    hidePanelWithAnimation()
                }
            }
        }
    }

    private fun showBubble() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            x = 32
            y = 360
        }
        bubbleParams = params
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        val view = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher_round)
            setBackgroundResource(android.R.drawable.picture_frame)
            setPadding(24, 24, 24, 24)
            var downRawX = 0f
            var downRawY = 0f
            var startX = 0
            var startY = 0
            var dragging = false
            var longPressTriggered = false
            val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
            val longPressRunnable = Runnable {
                if (!dragging) {
                    longPressTriggered = true
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    vibrateShort()
                    openMainActivity()
                }
            }
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX
                        downRawY = event.rawY
                        startX = params.x
                        startY = params.y
                        dragging = false
                        longPressTriggered = false
                        removeCallbacks(longPressRunnable)
                        postDelayed(longPressRunnable, longPressTimeout)
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - downRawX).toInt()
                        val dy = (event.rawY - downRawY).toInt()
                        if (!dragging && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) {
                            dragging = true
                            removeCallbacks(longPressRunnable)
                        }
                        if (dragging) {
                            params.x = startX + dx
                            params.y = startY + dy
                            windowManager.updateViewLayout(this, params)
                        }
                        true
                    }

                    MotionEvent.ACTION_UP -> {
                        removeCallbacks(longPressRunnable)
                        if (!dragging && !longPressTriggered) {
                            overlayViewModel.openCropSheet()
                        }
                        true
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        removeCallbacks(longPressRunnable)
                        true
                    }

                    else -> false
                }
            }
        }
        windowManager.addView(view, params)
        bubbleView = view
    }

    private fun showOrUpdatePanel(mode: OverlaySheetMode) {
        panelClosing = false
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val screenHeightPx = metrics.heightPixels
        val density = resources.displayMetrics.density
        val dockHeightPx = resources.displayMetrics.density.times(SheetDockOffset.value).roundToInt()
        val targetHeightPx = (screenHeightPx * if (mode == OverlaySheetMode.CROP) 0.88f else 0.92f)
            .roundToInt()
            .coerceAtLeast(dockHeightPx)

        panelScreenHeightPx = screenHeightPx
        panelHeightPx = targetHeightPx
        panelDockHeightPx = dockHeightPx
        panelHandleHeightPx = (28f * density).roundToInt()
        panelHandleWidthPx = (88f * density).roundToInt()
        if (panelView == null) {
            panelCurrentHeightPx = dockHeightPx
        } else {
            panelCurrentHeightPx = panelCurrentHeightPx.coerceIn(dockHeightPx, targetHeightPx)
        }

        val params = panelParams ?: WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            panelCurrentHeightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = screenHeightPx - panelCurrentHeightPx
        }
        params.width = WindowManager.LayoutParams.MATCH_PARENT
        params.height = panelCurrentHeightPx
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = (screenHeightPx - panelCurrentHeightPx).coerceAtLeast(0)
        panelParams = params

        val handleParams = panelHandleParams ?: WindowManager.LayoutParams(
            panelHandleWidthPx,
            panelHandleHeightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = screenHeightPx - panelCurrentHeightPx
        }
        handleParams.width = panelHandleWidthPx
        handleParams.height = panelHandleHeightPx
        handleParams.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        handleParams.x = 0
        handleParams.y = panelHandleY(screenHeightPx, panelCurrentHeightPx)
        panelHandleParams = handleParams

        if (panelView == null) {
            val composeView = ComposeView(this).apply {
                setViewTreeLifecycleOwner(this@OverlayService)
                setViewTreeViewModelStoreOwner(this@OverlayService)
                setViewTreeSavedStateRegistryOwner(this@OverlayService)
                setContent {
                    HanakoTheme {
                        OverlayPanel(
                            viewModel = overlayViewModel,
                            onDismiss = { overlayViewModel.closeSheet() },
                            panelHeightPx = panelHeightPx
                        )
                    }
                }
            }
            val panelRoot = FrameLayout(this).apply {
                setViewTreeLifecycleOwner(this@OverlayService)
                setViewTreeViewModelStoreOwner(this@OverlayService)
                setViewTreeSavedStateRegistryOwner(this@OverlayService)
                clipChildren = true
                clipToPadding = true
                addView(
                    composeView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        targetHeightPx
                    )
                )
            }
            val handleView = createPanelHandleView()
            panelView = panelRoot
            panelContentView = composeView
            panelHandleView = handleView
            windowManager.addView(panelRoot, params)
            windowManager.addView(handleView, handleParams)
            applyPanelHeight(panelCurrentHeightPx)
            animatePanelHeight(
                fromHeightPx = panelCurrentHeightPx,
                toHeightPx = targetHeightPx
            )
        } else {
            runCatching { windowManager.updateViewLayout(panelView, params) }
            runCatching { windowManager.updateViewLayout(panelHandleView, handleParams) }
            applyPanelHeight(panelCurrentHeightPx)
        }
    }

    private fun createPanelHandleView(): FrameLayout {
        var dragStartRawY = 0f
        var dragStartHeightPx = 0
        return FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            addView(
                FrameLayout(this@OverlayService).apply {
                    background = GradientDrawable().apply {
                        setColor(Color.rgb(86, 86, 86))
                        cornerRadius = 999f * resources.displayMetrics.density
                    }
                },
                FrameLayout.LayoutParams(
                    (68f * resources.displayMetrics.density).roundToInt(),
                    (8f * resources.displayMetrics.density).roundToInt()
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    topMargin = (10f * resources.displayMetrics.density).roundToInt()
                }
            )
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        panelAnimationJob?.cancel()
                        dragStartRawY = event.rawY
                        dragStartHeightPx = panelCurrentHeightPx
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val nextHeight = (dragStartHeightPx - (event.rawY - dragStartRawY))
                            .roundToInt()
                        updatePanelHeight(nextHeight)
                        true
                    }

                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> true

                    else -> false
                }
            }
        }
    }

    private fun updatePanelHeight(heightPx: Int) {
        if (panelDockHeightPx <= 0 || panelHeightPx <= 0) return
        applyPanelHeight(heightPx.coerceIn(panelDockHeightPx, panelHeightPx))
    }

    private fun applyPanelHeight(heightPx: Int) {
        val view = panelView ?: return
        val params = panelParams
        val handleParams = panelHandleParams
        if (panelHeightPx <= 0 || panelScreenHeightPx <= 0) return
        panelCurrentHeightPx = heightPx.coerceIn(0, panelHeightPx)
        if (params != null) {
            params.y = (panelScreenHeightPx - panelCurrentHeightPx).coerceAtLeast(0)
            params.height = panelCurrentHeightPx.coerceAtLeast(1)
            runCatching { windowManager.updateViewLayout(view, params) }
        }
        if (handleParams != null) {
            handleParams.y = panelHandleY(panelScreenHeightPx, panelCurrentHeightPx)
            runCatching { windowManager.updateViewLayout(panelHandleView, handleParams) }
        }
    }

    private fun panelHandleY(screenHeightPx: Int, currentHeightPx: Int): Int {
        val offsetPx = (PanelHandleYOffset.value * resources.displayMetrics.density).roundToInt()
        return (screenHeightPx - currentHeightPx - offsetPx).coerceAtLeast(0)
    }

    private fun animatePanelHeight(
        fromHeightPx: Int,
        toHeightPx: Int,
        onEnd: (() -> Unit)? = null
    ) {
        panelAnimationJob?.cancel()
        panelAnimationJob = serviceScope.launch {
            val start = fromHeightPx.coerceIn(0, panelHeightPx.coerceAtLeast(fromHeightPx))
            val end = toHeightPx.coerceIn(0, panelHeightPx.coerceAtLeast(toHeightPx))
            animatePanelHeightSegment(
                startHeightPx = start,
                endHeightPx = end,
                durationMs = SheetAnimationDurationMs,
                easing = ::easeOutCubic
            )
            updatePanelHeightAllowZero(end)
            onEnd?.invoke()
        }
    }

    private suspend fun animatePanelHeightSegment(
        startHeightPx: Int,
        endHeightPx: Int,
        durationMs: Int,
        easing: (Float) -> Float
    ) {
        val startTimeMs = android.os.SystemClock.uptimeMillis()
        while (currentCoroutineContext().isActive) {
            val elapsed = android.os.SystemClock.uptimeMillis() - startTimeMs
            val fraction = (elapsed.toFloat() / durationMs).coerceIn(0f, 1f)
            val eased = easing(fraction)
            val height = (startHeightPx + (endHeightPx - startHeightPx) * eased).roundToInt()
            updatePanelHeightAllowZero(height)
            if (fraction >= 1f) break
            kotlinx.coroutines.delay(16L)
        }
        updatePanelHeightAllowZero(endHeightPx)
    }

    private fun updatePanelHeightAllowZero(heightPx: Int) {
        applyPanelHeight(heightPx)
    }

    private fun easeOutCubic(fraction: Float): Float {
        return 1f - (1f - fraction).let { it * it * it }
    }

    private fun hidePanelWithAnimation() {
        if (panelClosing) return
        val view = panelView ?: return
        panelClosing = true
        val startHeight = panelCurrentHeightPx
        animatePanelHeight(
            fromHeightPx = startHeight,
            toHeightPx = 0
        ) {
            if (panelView === view) {
                removePanelNow()
            }
        }
    }

    private fun showOrUpdatePanelDeprecated() {
        if (panelView != null) return
        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeViewModelStoreOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)
            setContent {
                HanakoTheme {
                    OverlayPanel(
                        viewModel = overlayViewModel,
                        onDismiss = { overlayViewModel.closeSheet() }
                    )
                }
            }
        }
    }

    private fun dismissPanel() {
        panelAnimationJob?.cancel()
        panelAnimationJob = null
        panelClosing = false
        removePanelNow()
    }

    private fun removePanelNow() {
        panelHandleView?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        panelView?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        panelHandleView = null
        panelContentView = null
        panelView = null
        panelHandleParams = null
        panelParams = null
        panelCurrentHeightPx = 0
        panelClosing = false
    }

    private fun showStableTestPanel() {
        dismissPanel()
        dismissStableTestPanel()

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val screenHeightPx = metrics.heightPixels
        val density = resources.displayMetrics.density
        val minHeightPx = (88f * density).roundToInt()
        val maxHeightPx = (screenHeightPx * 0.92f).roundToInt().coerceAtLeast(minHeightPx)
        var currentHeightPx = (screenHeightPx * 0.36f).roundToInt().coerceIn(minHeightPx, maxHeightPx)

        val visualParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            maxHeightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = screenHeightPx - maxHeightPx
        }

        val visualRoot = FrameLayout(this)
        val sheet = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadii = floatArrayOf(
                    28f * density, 28f * density,
                    28f * density, 28f * density,
                    0f, 0f,
                    0f, 0f
                )
            }
        }
        visualRoot.addView(
            sheet,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                maxHeightPx
            )
        )

        val handleHeightPx = (56f * density).roundToInt()
        val handleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            handleHeightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = screenHeightPx - currentHeightPx
        }

        fun applyHeight(heightPx: Int) {
            currentHeightPx = heightPx.coerceIn(minHeightPx, maxHeightPx)
            sheet.translationY = (maxHeightPx - currentHeightPx).toFloat()
            handleParams.y = screenHeightPx - currentHeightPx
            runCatching { windowManager.updateViewLayout(stableTestHandleView, handleParams) }
        }

        var dragStartRawY = 0f
        var dragStartHeightPx = currentHeightPx
        val handle = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        dragStartRawY = event.rawY
                        dragStartHeightPx = currentHeightPx
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val nextHeight = (dragStartHeightPx - (event.rawY - dragStartRawY))
                            .roundToInt()
                            .coerceIn(minHeightPx, maxHeightPx)
                        applyHeight(nextHeight)
                        true
                    }

                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> true

                    else -> false
                }
            }
        }

        stableTestPanelParams = visualParams
        stableTestHandleParams = handleParams
        stableTestPanelView = visualRoot
        stableTestHandleView = handle
        sheet.translationY = (maxHeightPx - currentHeightPx).toFloat()
        windowManager.addView(visualRoot, visualParams)
        windowManager.addView(handle, handleParams)
    }

    private fun dismissStableTestPanel() {
        stableTestHandleView?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        stableTestPanelView?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        stableTestHandleView = null
        stableTestPanelView = null
        stableTestHandleParams = null
        stableTestPanelParams = null
    }

    companion object {
        const val ACTION_STOP = "fun.kirari.hanako.overlay.STOP"
        const val ACTION_TEST_SHEET_STABLE = "fun.kirari.hanako.overlay.TEST_SHEET_STABLE"
        internal const val CHANNEL_ID = "overlay_service"
        private const val NOTIFICATION_ID = 1001
    }
}
