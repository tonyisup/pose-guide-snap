package com.tonyisup.poseguidesnap.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Rational
import android.view.Surface
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ViewPort
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tonyisup.poseguidesnap.camera.AnalyzedCameraFrame
import com.tonyisup.poseguidesnap.camera.CameraControllerStatus
import com.tonyisup.poseguidesnap.camera.CameraXController
import com.tonyisup.poseguidesnap.camera.NormalizedPoint
import com.tonyisup.poseguidesnap.camera.PixelSize
import com.tonyisup.poseguidesnap.camera.PreviewFillCenterTransform
import com.tonyisup.poseguidesnap.data.GuidedReferenceSnapshot
import com.tonyisup.poseguidesnap.domain.model.Landmark
import com.tonyisup.poseguidesnap.domain.model.PoseLandmark
import com.tonyisup.poseguidesnap.ui.navigation.AppNavHost
import com.tonyisup.poseguidesnap.ui.camera.CameraXJournaledStillCaptureWriter
import com.tonyisup.poseguidesnap.ui.camera.GuidedCameraControls
import com.tonyisup.poseguidesnap.ui.camera.GuidedCameraPhase
import com.tonyisup.poseguidesnap.ui.camera.GuidedCameraViewModel

private val WarmNearBlack = Color(0xFF171411)
private val WarmPanel = Color(0xEB211D19)
private val WarmOffWhite = Color(0xFFF6F0E6)
private val WarmMuted = Color(0xFFC9BFB0)
private val WarmAccent = Color(0xFFE6B86A)

@Composable
fun App(lifecycleOwner: LifecycleOwner) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = WarmNearBlack,
            surface = WarmPanel,
            onBackground = WarmOffWhite,
            onSurface = WarmOffWhite,
            primary = WarmAccent,
            onPrimary = WarmNearBlack,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            AppNavHost(lifecycleOwner = lifecycleOwner)
        }
    }
}

@Composable
internal fun StartedSessionCameraDestination(
    lifecycleOwner: LifecycleOwner,
    owner: GuidedCameraViewModel,
    onStop: () -> Unit,
) {
    CameraPermissionGate(
        lifecycleOwner = lifecycleOwner,
        owner = owner,
        onStop = onStop,
    )
}

@Composable
private fun CameraPermissionGate(
    lifecycleOwner: LifecycleOwner,
    owner: GuidedCameraViewModel,
    onStop: () -> Unit,
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val preferences = remember(context) { context.getSharedPreferences("camera-permission", Context.MODE_PRIVATE) }
    var hasRequested by remember(preferences) { mutableStateOf(preferences.getBoolean("requested", false)) }
    var shouldShowRationale by remember(activity) {
        mutableStateOf(activity?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == true)
    }
    var settingsUnavailable by remember { mutableStateOf(false) }
    var hasCameraPermission by remember(context) {
        mutableStateOf(isCameraPermissionGranted(context))
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCameraPermission = granted
        hasRequested = true
        preferences.edit { putBoolean("requested", true) }
        shouldShowRationale = activity?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == true
    }

    DisposableEffect(lifecycleOwner, context, activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasCameraPermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA,
                ) == PackageManager.PERMISSION_GRANTED
                shouldShowRationale = activity?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (!hasCameraPermission) {
        CameraPermissionScreen(
            recovery = cameraPermissionRecovery(hasRequested, shouldShowRationale),
            settingsUnavailable = settingsUnavailable,
            onAllowCamera = {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            },
            onOpenSettings = {
                try {
                    context.startActivity(Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    ))
                } catch (_: ActivityNotFoundException) {
                    settingsUnavailable = true
                }
            },
        )
    } else {
        LiveCameraScreen(
            lifecycleOwner = lifecycleOwner,
            owner = owner,
            onStop = onStop,
        )
    }
}

@Composable
internal fun CameraPermissionScreen(
    recovery: CameraPermissionRecovery,
    settingsUnavailable: Boolean = false,
    onAllowCamera: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WarmNearBlack)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = "Pose Guide Snap",
            color = WarmOffWhite,
            fontSize = 30.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics {
                contentDescription = "Title: Pose Guide Snap"
            },
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Live camera is needed to show the rear preview and on-device pose landmarks. When you choose Capture, the app takes three private photos for this pose.",
            color = WarmMuted,
            fontSize = 17.sp,
            lineHeight = 24.sp,
        )
        if (recovery != CameraPermissionRecovery.INITIAL) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = if (recovery == CameraPermissionRecovery.SETTINGS) {
                    "Camera access is off. You can enable Camera in this app's permissions in Settings, then return here."
                } else {
                    "Camera access was denied. You can try again when you're ready, or go back to your shoots."
                },
                color = WarmMuted,
            )
        }
        if (settingsUnavailable) {
            Text("Open your phone's Settings, choose Apps, then Pose Guide Snap → Permissions → Camera.", color = WarmMuted)
        }
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = if (recovery == CameraPermissionRecovery.SETTINGS) onOpenSettings else onAllowCamera,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .semantics {
                    contentDescription = "Permission action: ${recovery.actionLabel}"
                },
            colors = ButtonDefaults.buttonColors(
                containerColor = WarmAccent,
                contentColor = WarmNearBlack,
            ),
            shape = RoundedCornerShape(10.dp),
        ) {
            Text(recovery.actionLabel, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun LiveCameraScreen(
    lifecycleOwner: LifecycleOwner,
    owner: GuidedCameraViewModel,
    onStop: () -> Unit,
) {
    val context = LocalContext.current
    val applicationContext = context.applicationContext
    var latestFrame by remember { mutableStateOf<AnalyzedCameraFrame?>(null) }
    var cameraStatus by remember { mutableStateOf(CameraControllerStatus.IDLE) }
    var hasRecoverableFailure by remember { mutableStateOf(false) }
    var retryRequest by remember { mutableIntStateOf(0) }
    val guidedState by owner.state.collectAsStateWithLifecycle()
    val controller = remember(applicationContext) {
        CameraXController.create(
            context = applicationContext,
            onFrame = { analyzedFrame ->
                latestFrame = analyzedFrame
                hasRecoverableFailure = false
            },
            onState = { state ->
                cameraStatus = state.status
                owner.setCameraReady(state.status == CameraControllerStatus.READY)
            },
            onFailure = {
                hasRecoverableFailure = true
                owner.setCameraReady(false)
            },
        )
    }
    val mainExecutor = remember(applicationContext) {
        ContextCompat.getMainExecutor(applicationContext)
    }
    val captureWriter = remember(controller, mainExecutor) {
        CameraXJournaledStillCaptureWriter(
            imageCapture = controller::requireImageCapture,
            mainExecutor = mainExecutor,
            callbackExecutor = mainExecutor,
        )
    }

    DisposableEffect(controller, owner, captureWriter) {
        owner.attachWriter(captureWriter)
        onDispose {
            owner.detachWriter(captureWriter)
            owner.setCameraReady(false)
            controller.close()
        }
    }

    LaunchedEffect(guidedState.phase) {
        if (guidedState.phase == GuidedCameraPhase.STOPPED) onStop()
    }
    BackHandler(enabled = guidedState.phase != GuidedCameraPhase.STOPPED) {
        owner.stop()
    }

    val diagnostics = LiveCameraDiagnostics.from(
        cameraStatus = cameraStatus,
        poseObservation = latestFrame?.poseObservation,
        reference = guidedState.reference,
        hasRecoverableFailure = hasRecoverableFailure,
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WarmNearBlack),
    ) {
        CameraPreview(
            controller = controller,
            lifecycleOwner = lifecycleOwner,
            frame = latestFrame,
            reference = guidedState.reference,
            retryRequest = retryRequest,
        )
        GuidedCameraControls(
            state = guidedState,
            onCapture = owner::manualCapture,
            onStop = owner::stop,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(16.dp),
        )
        StatusPanel(
            diagnostics = diagnostics,
            onRetry = { retryRequest += 1 },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(16.dp),
        )
    }
}

@Composable
private fun CameraPreview(
    controller: CameraXController,
    lifecycleOwner: LifecycleOwner,
    frame: AnalyzedCameraFrame?,
    reference: GuidedReferenceSnapshot?,
    retryRequest: Int,
) {
    val context = LocalContext.current
    val preview = remember(context) {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    var previewSize by remember { mutableStateOf(IntSize.Zero) }
    var displayRotation by remember { mutableIntStateOf(Surface.ROTATION_0) }
    var lastBindKey by remember(controller) { mutableStateOf<PreviewBindKey?>(null) }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { preview },
            modifier = Modifier
                .matchParentSize()
                .onSizeChanged { size ->
                    previewSize = size
                    displayRotation = preview.display?.rotation ?: Surface.ROTATION_0
                }
                .semantics {
                    contentDescription = "Rear camera preview"
                },
            update = { view ->
                val currentRotation = view.display?.rotation ?: Surface.ROTATION_0
                if (displayRotation != currentRotation) {
                    displayRotation = currentRotation
                }
            },
        )

        PoseOverlay(
            frame = frame,
            reference = reference,
            modifier = Modifier.matchParentSize(),
        )
    }

    LaunchedEffect(
        controller,
        lifecycleOwner,
        preview,
        previewSize,
        displayRotation,
        retryRequest,
    ) {
        if (previewSize.width <= 0 || previewSize.height <= 0) {
            return@LaunchedEffect
        }
        val bindKey = PreviewBindKey(
            width = previewSize.width,
            height = previewSize.height,
            rotation = displayRotation,
            retryRequest = retryRequest,
        )
        if (lastBindKey == bindKey) {
            return@LaunchedEffect
        }
        val viewPort: ViewPort = ViewPort.Builder(
            Rational(bindKey.width, bindKey.height),
            bindKey.rotation,
        )
            .setScaleType(ViewPort.FILL_CENTER)
            .build()
        controller.bind(
            lifecycleOwner = lifecycleOwner,
            surfaceProvider = preview.surfaceProvider,
            viewPort = viewPort,
            targetRotation = bindKey.rotation,
        )
        lastBindKey = bindKey
    }
}

@Composable
private fun PoseOverlay(
    frame: AnalyzedCameraFrame?,
    reference: GuidedReferenceSnapshot?,
    modifier: Modifier = Modifier,
) {
    val liveLandmarks = frame?.poseObservation?.landmarks.orEmpty()
    val referenceLandmarks = reference?.landmarks.orEmpty()
    Canvas(
        modifier = modifier.semantics {
            contentDescription =
                "Pose overlay: reference ${referenceLandmarks.size} landmarks; live ${liveLandmarks.size} landmarks"
        },
    ) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val previewSize = PixelSize(size.width.toDouble(), size.height.toDouble())
        reference?.let {
            drawSkeleton(
                landmarks = referenceLandmarks,
                transform = PreviewFillCenterTransform(
                    PixelSize(it.imageSize.width.toDouble(), it.imageSize.height.toDouble()),
                    previewSize,
                ),
                lineColor = WarmAccent.copy(alpha = 0.38f),
                pointColor = WarmMuted.copy(alpha = 0.55f),
                lineWidth = 5.dp.toPx(),
                pointRadius = 6.dp.toPx(),
            )
        }
        frame?.let {
            drawSkeleton(
                landmarks = liveLandmarks,
                transform = PreviewFillCenterTransform(
                    it.coordinateTransform.uprightContentPixelSize,
                    previewSize,
                ),
                lineColor = WarmAccent,
                pointColor = WarmOffWhite,
                lineWidth = 4.dp.toPx(),
                pointRadius = 5.dp.toPx(),
            )
        }
    }
}

private fun DrawScope.drawSkeleton(
    landmarks: List<Landmark>,
    transform: PreviewFillCenterTransform,
    lineColor: Color,
    pointColor: Color,
    lineWidth: Float,
    pointRadius: Float,
) {
    val landmarksByType = landmarks.associateBy(Landmark::type)
    COCO_SKELETON_EDGES.forEach { (startType, endType) ->
        val start = landmarksByType[startType] ?: return@forEach
        val end = landmarksByType[endType] ?: return@forEach
        val startPoint = transform.contentToPreview(NormalizedPoint(start.x, start.y))
        val endPoint = transform.contentToPreview(NormalizedPoint(end.x, end.y))
        drawLine(
            color = lineColor,
            start = Offset(startPoint.x.toFloat(), startPoint.y.toFloat()),
            end = Offset(endPoint.x.toFloat(), endPoint.y.toFloat()),
            strokeWidth = lineWidth,
            cap = StrokeCap.Round,
        )
    }
    landmarks.forEach { landmark ->
        val point = transform.contentToPreview(NormalizedPoint(landmark.x, landmark.y))
        drawCircle(
            color = pointColor,
            radius = pointRadius,
            center = Offset(point.x.toFloat(), point.y.toFloat()),
        )
    }
}

@Composable
private fun StatusPanel(
    diagnostics: LiveCameraDiagnostics,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(WarmPanel, RoundedCornerShape(12.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            text = "Pose Guide Snap",
            color = WarmOffWhite,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics {
                contentDescription = "Title: Pose Guide Snap"
            },
        )
        DiagnosticText(
            text = diagnostics.cameraLabel,
            contentDescription = diagnostics.cameraLabel,
        )
        DiagnosticText(
            text = diagnostics.personLabel,
            contentDescription = diagnostics.personLabel,
        )
        DiagnosticText(
            text = diagnostics.landmarkLabel,
            contentDescription = diagnostics.landmarkLabel,
        )
        DiagnosticText(
            text = diagnostics.referenceLabel,
            contentDescription = diagnostics.referenceLabel,
        )
        DiagnosticText(
            text = diagnostics.framingLabel,
            contentDescription = diagnostics.framingLabel,
        )
        DiagnosticText(
            text = diagnostics.coverageLabel,
            contentDescription = diagnostics.coverageLabel,
        )
        DiagnosticText(
            text = diagnostics.angularLabel,
            contentDescription = diagnostics.angularLabel,
        )
        DiagnosticText(
            text = diagnostics.positionalLabel,
            contentDescription = diagnostics.positionalLabel,
        )
        DiagnosticText(
            text = diagnostics.overallLabel,
            contentDescription = diagnostics.overallLabel,
        )
        DiagnosticText(
            text = diagnostics.mirrorLabel,
            contentDescription = diagnostics.mirrorLabel,
        )
        DiagnosticText(
            text = diagnostics.captureLockLabel,
            contentDescription = diagnostics.captureLockLabel,
        )
        diagnostics.recoverableActionText?.let { actionText ->
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onRetry,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics {
                        contentDescription = "Camera recovery action: $actionText"
                    },
                colors = ButtonDefaults.buttonColors(
                    containerColor = WarmAccent,
                    contentColor = WarmNearBlack,
                ),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(actionText, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun DiagnosticText(
    text: String,
    contentDescription: String,
) {
    Text(
        text = text,
        color = WarmOffWhite,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        modifier = Modifier.semantics {
            this.contentDescription = contentDescription
        },
    )
}

private fun isCameraPermissionGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.CAMERA,
    ) == PackageManager.PERMISSION_GRANTED

private data class PreviewBindKey(
    val width: Int,
    val height: Int,
    val rotation: Int,
    val retryRequest: Int,
)

private val COCO_SKELETON_EDGES = listOf(
    PoseLandmark.NOSE to PoseLandmark.LEFT_EYE,
    PoseLandmark.NOSE to PoseLandmark.RIGHT_EYE,
    PoseLandmark.LEFT_EYE to PoseLandmark.LEFT_EAR,
    PoseLandmark.RIGHT_EYE to PoseLandmark.RIGHT_EAR,
    PoseLandmark.LEFT_SHOULDER to PoseLandmark.RIGHT_SHOULDER,
    PoseLandmark.LEFT_SHOULDER to PoseLandmark.LEFT_ELBOW,
    PoseLandmark.LEFT_ELBOW to PoseLandmark.LEFT_WRIST,
    PoseLandmark.RIGHT_SHOULDER to PoseLandmark.RIGHT_ELBOW,
    PoseLandmark.RIGHT_ELBOW to PoseLandmark.RIGHT_WRIST,
    PoseLandmark.LEFT_SHOULDER to PoseLandmark.LEFT_HIP,
    PoseLandmark.RIGHT_SHOULDER to PoseLandmark.RIGHT_HIP,
    PoseLandmark.LEFT_HIP to PoseLandmark.RIGHT_HIP,
    PoseLandmark.LEFT_HIP to PoseLandmark.LEFT_KNEE,
    PoseLandmark.LEFT_KNEE to PoseLandmark.LEFT_ANKLE,
    PoseLandmark.RIGHT_HIP to PoseLandmark.RIGHT_KNEE,
    PoseLandmark.RIGHT_KNEE to PoseLandmark.RIGHT_ANKLE,
)
