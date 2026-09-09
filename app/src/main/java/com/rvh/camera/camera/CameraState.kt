package com.rvh.camera.camera

sealed interface CameraState {
    data object Initializing : CameraState
    data object PermissionRequired : CameraState
    data object Opening : CameraState
    data object Ready : CameraState
    data class Error(val message: String) : CameraState
}
