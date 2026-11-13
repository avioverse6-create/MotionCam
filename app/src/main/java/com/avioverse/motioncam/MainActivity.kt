package com.avioverse.motioncam

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MotionCamApp() }
    }
}

private val darkBg = Color(0xFF111318)
private val cardBg = Color(0xFF22262E)
private val lavender = Color(0xFFD0BCFF)

@Composable
private fun MotionCamApp() {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var cameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var audioPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        cameraPermission = result[Manifest.permission.CAMERA] == true || ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        audioPermission = result[Manifest.permission.RECORD_AUDIO] == true || ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var photoCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var recordingNow by remember { mutableStateOf(false) }
    var videoMode by remember { mutableStateOf(false) }
    var frontCamera by remember { mutableStateOf(false) }
    var flash by remember { mutableStateOf(false) }
    var fpsMode by remember { mutableIntStateOf(0) }
    var fpsChoices by remember { mutableStateOf(listOf(30)) }
    var message by remember { mutableStateOf("Готово к съёмке") }

    LaunchedEffect(Unit) {
        if (!cameraPermission) permissionRequest.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        else if (!audioPermission) permissionRequest.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
    }

    LaunchedEffect(previewView, cameraPermission, frontCamera) {
        val view = previewView
        if (view != null && cameraPermission) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try {
                    val provider = future.get()
                    val selector = CameraSelector.Builder()
                        .requireLensFacing(if (frontCamera) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK)
                        .build()
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                    val photo = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                    val recorder = Recorder.Builder().setQualitySelector(
                        QualitySelector.from(Quality.HIGHEST, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
                    ).build()
                    val video = VideoCapture.withOutput(recorder)
                    provider.unbindAll()
                    val bound = provider.bindToLifecycle(owner, selector, preview, photo, video)
                    photoCapture = photo
                    videoCapture = video
                    camera = bound
                    flash = false
                    val ranges = Camera2CameraInfo.from(bound.cameraInfo)
                        .getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                    fpsChoices = (ranges?.map { it.upper } ?: listOf(30)).filter { it >= 24 }.distinct().sorted()
                    message = "Камера готова"
                } catch (_: Exception) {
                    message = "Не удалось запустить камеру"
                }
            }, ContextCompat.getMainExecutor(context))
        }
    }

    LaunchedEffect(camera, fpsMode) {
        val current = camera ?: return@LaunchedEffect
        val ranges = Camera2CameraInfo.from(current.cameraInfo)
            .getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return@LaunchedEffect
        if (fpsMode == 0) {
            Camera2CameraControl.from(current.cameraControl).clearCaptureRequestOptions()
        } else {
            val target = if (fpsMode == 999) ranges.maxOfOrNull { it.upper } ?: 30 else fpsMode
            val range = ranges.filter { it.upper >= target }.minByOrNull { it.upper }
            if (range != null) {
                val options = CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
                    .build()
                Camera2CameraControl.from(current.cameraControl).setCaptureRequestOptions(options)
            }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = lavender, background = darkBg, surface = cardBg, onSurface = Color.White)) {
        Surface(Modifier.fillMaxSize(), color = darkBg) {
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("MOTIONCAM", fontSize = 20.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                        Text("КАМЕРА • PHOTO / VIDEO", color = Color(0xFF9AA0AA), fontSize = 10.sp, letterSpacing = 1.sp)
                    }
                    Surface(color = cardBg, shape = CircleShape) {
                        Text(if (recordingNow) "● REC" else "● LIVE", color = if (recordingNow) Color(0xFFFF7777) else Color(0xFF9FE3B1), modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (cameraPermission) {
                        AndroidView(
                            factory = { ctx ->
                                PreviewView(ctx).also {
                                    it.scaleType = PreviewView.ScaleType.FILL_CENTER
                                    it.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                                    previewView = it
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.PhotoCamera, null, tint = lavender, modifier = Modifier.size(48.dp))
                            Text("Разреши доступ к камере", color = Color.White, modifier = Modifier.padding(12.dp))
                            Button(onClick = { permissionRequest.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) }) { Text("Разрешить") }
                        }
                    }
                    Surface(Modifier.align(Alignment.TopStart).padding(10.dp), color = Color(0xAA000000), shape = RoundedCornerShape(12.dp)) {
                        Text(if (videoMode) "ВИДЕО • " + (if (fpsMode == 0) "AUTO" else if (fpsMode == 999) "MAX" else "$fpsMode FPS") else "ФОТО", color = Color.White, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    ModeChip("Фото", !videoMode) { if (!recordingNow) videoMode = false }
                    Spacer(Modifier.width(10.dp))
                    ModeChip("Видео", videoMode) { if (!recordingNow) videoMode = true }
                }
                if (videoMode) {
                    Spacer(Modifier.height(14.dp))
                    Text("ЧАСТОТА КАДРОВ", color = Color(0xFF9AA0AA), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        listOf(0, 60, 90, 120, 999).forEach { value ->
                            val label = when (value) { 0 -> "AUTO"; 999 -> "MAX"; else -> value.toString() }
                            val available = value == 0 || value == 999 || fpsChoices.any { it >= value }
                            Surface(
                                onClick = { if (available && !recordingNow) fpsMode = value },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                color = if (fpsMode == value) lavender else cardBg
                            ) {
                                Box(Modifier.padding(vertical = 11.dp), contentAlignment = Alignment.Center) {
                                    Text(label, color = if (fpsMode == value) Color(0xFF33245A) else if (available) Color.White else Color(0xFF666B75), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                    Text("Поддерживаемые режимы: " + fpsChoices.joinToString(" / ") + " FPS", color = Color(0xFF9299A4), fontSize = 10.sp, modifier = Modifier.padding(top = 7.dp))
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(
                        onClick = { if (!recordingNow) frontCamera = !frontCamera },
                        enabled = !recordingNow,
                        shape = CircleShape
                    ) { Icon(Icons.Default.Cameraswitch, "Сменить камеру") }
                    Button(
                        onClick = {
                            if (videoMode) {
                                val active = recording
                                if (active != null) {
                                    active.stop()
                                    recording = null
                                } else {
                                    val capture = videoCapture
                                    if (capture == null) message = "Камера ещё запускается"
                                    else {
                                        val filename = "MotionCam_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
                                        val values = ContentValues().apply {
                                            put(MediaStore.Video.Media.DISPLAY_NAME, filename)
                                            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                                            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MotionCam")
                                        }
                                        val output = MediaStoreOutputOptions.Builder(context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                                            .setContentValues(values).build()
                                        var pending = capture.output.prepareRecording(context, output)
                                        if (audioPermission) pending = pending.withAudioEnabled()
                                        recording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
                                            when (event) {
                                                is VideoRecordEvent.Start -> { recordingNow = true; message = "Идёт запись видео" }
                                                is VideoRecordEvent.Finalize -> {
                                                    recordingNow = false
                                                    recording = null
                                                    message = if (!event.hasError()) "Видео сохранено в галерею" else "Ошибка записи видео"
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                val capture = photoCapture
                                if (capture == null) message = "Камера ещё запускается"
                                else {
                                    val filename = "MotionCam_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
                                    val values = ContentValues().apply {
                                        put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MotionCam")
                                    }
                                    capture.flashMode = if (flash && !frontCamera) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
                                    val output = ImageCapture.OutputFileOptions.Builder(context.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values).build()
                                    capture.takePicture(output, ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
                                        override fun onImageSaved(result: ImageCapture.OutputFileResults) { message = "Фото сохранено в галерею" }
                                        override fun onError(exception: ImageCaptureException) { message = "Ошибка сохранения фото" }
                                    })
                                }
                            }
                        },
                        modifier = Modifier.size(width = 84.dp, height = 58.dp),
                        shape = RoundedCornerShape(22.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = if (recordingNow) Color(0xFFB3261E) else lavender, contentColor = Color(0xFF33245A))
                    ) {
                        Icon(if (videoMode) Icons.Default.Videocam else Icons.Default.PhotoCamera, null, modifier = Modifier.size(26.dp))
                    }
                    FilledTonalButton(onClick = { flash = !flash }, enabled = !frontCamera && !recordingNow, shape = CircleShape) {
                        Icon(if (flash) Icons.Default.FlashOn else Icons.Default.FlashOff, "Вспышка")
                    }
                }
                Spacer(Modifier.height(9.dp))
                Text(message, color = Color(0xFFB9C0CA), fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
                Text("Сохранение в галерею • " + if (audioPermission) "звук включён" else "видео без звука", color = Color(0xFF727985), fontSize = 10.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = CircleShape, color = if (selected) lavender else cardBg) {
        Text(label, color = if (selected) Color(0xFF33245A) else Color.White, modifier = Modifier.padding(horizontal = 24.dp, vertical = 11.dp), fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}
