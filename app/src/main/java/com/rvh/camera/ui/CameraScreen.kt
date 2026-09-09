package com.rvh.camera.ui

import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.viewinterop.AndroidView
import com.rvh.camera.camera.CameraController

@Composable
fun CameraScreen(
    permissionGranted: Boolean,
    controller: CameraController,
    onCapture: () -> Unit,
    onError: (String) -> Unit,
    diagnosticText: () -> String,
    onShareDiagnostics: () -> Unit,
    onCopyDiagnostics: () -> Unit,
    onClearDiagnostics: () -> Unit
) {
    MaterialTheme {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        val texture = remember { TextureView(context) }
        var cameraReady by remember(permissionGranted) { mutableStateOf(false) }
        var diagnosticsOpen by remember { mutableStateOf(false) }
        var report by remember { mutableStateOf("") }

        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.BottomCenter
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { texture }
            )

            DisposableEffect(permissionGranted, texture, lifecycleOwner) {
                if (permissionGranted) {
                    val openIfAvailable = {
                        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && texture.isAvailable) {
                            texture.surfaceTexture?.let { surface ->
                                controller.open(texture, texture.width, texture.height, { cameraReady = true }, onError)
                            }
                        }
                    }

                    val lifecycleObserver = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_START -> openIfAvailable()
                            Lifecycle.Event.ON_STOP -> {
                                cameraReady = false
                                controller.stopPreview()
                            }
                            else -> Unit
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(lifecycleObserver)

                    val listener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                            controller.open(texture, width, height, { cameraReady = true }, onError)
                        }

                        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                            controller.updatePreviewTransform()
                        }

                        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                            controller.stopPreview()
                            return true
                        }

                        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                    }
                    texture.surfaceTextureListener = listener
                    openIfAvailable()

                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
                        texture.surfaceTextureListener = null
                        cameraReady = false
                        controller.stopPreview()
                    }
                } else {
                    cameraReady = false
                    controller.stopPreview()
                    onDispose { }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onCapture,
                    enabled = permissionGranted && cameraReady
                ) {
                    Text("SHUTTER")
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = {
                    report = diagnosticText()
                    diagnosticsOpen = true
                }) {
                    Text("DIAGNOSTICS")
                }
            }

            if (diagnosticsOpen) {
                AlertDialog(
                    onDismissRequest = { diagnosticsOpen = false },
                    title = { Text("RVH Diagnostics") },
                    text = {
                        Text(
                            text = report,
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            report = diagnosticText()
                        }) { Text("REFRESH") }
                    },
                    dismissButton = {
                        Row {
                            TextButton(onClick = onShareDiagnostics) { Text("SHARE") }
                            TextButton(onClick = onCopyDiagnostics) { Text("COPY") }
                            TextButton(onClick = {
                                onClearDiagnostics()
                                report = diagnosticText()
                            }) { Text("CLEAR") }
                            TextButton(onClick = { diagnosticsOpen = false }) { Text("CLOSE") }
                        }
                    }
                )
            }
        }
    }
}
