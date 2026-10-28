package com.airecorder.mvp

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.airecorder.mvp.core.ble.BleLiveStreamRecorder
import com.airecorder.mvp.core.ble.BleConnectionState
import com.airecorder.mvp.core.ble.DiscoveredRecorder
import com.airecorder.mvp.core.ble.LivePreviewState
import com.airecorder.mvp.core.ble.PublicBlePreviewExporter
import com.airecorder.mvp.core.ble.RecorderBleProtocol
import com.airecorder.mvp.core.ble.isActive
import com.airecorder.mvp.core.audio.ConcentusRawOpusDecoder
import com.airecorder.mvp.core.audio.PhoneTestRecorder
import com.airecorder.mvp.core.database.AudioFormat
import com.airecorder.mvp.core.database.ProcessingState
import com.airecorder.mvp.core.database.DeviceEntity
import com.airecorder.mvp.core.database.RecordingDetail
import com.airecorder.mvp.core.database.RecordingEntity
import com.airecorder.mvp.sync.SyncCoordinator
import com.airecorder.mvp.sync.RecorderRecoveryState
import com.airecorder.mvp.sync.SyncState
import com.airecorder.mvp.sync.isRunning
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt
import java.io.File
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MaterialTheme { RecorderApp(viewModel) } }
    }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as RecorderApplication
    private val ble = app.container.ble
    private val sync = app.container.syncCoordinator
    private val syncSession = app.container.syncSession
    private val liveStreamRecorder = BleLiveStreamRecorder(
        appFilesDir = application.filesDir,
        recordings = app.container.recordings,
        frames = ble.liveFrames,
        publicExporter = PublicBlePreviewExporter(application)
    )
    private val phoneTestRecorder = PhoneTestRecorder(application.cacheDir)
    val library = app.container.recordings.observeLibrary().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val connection = ble.connection
    val status = ble.status
    val livePreview = ble.livePreview
    val syncState = sync.state
    val recorderRecovery = syncSession.recovery
    private val _syncTransport = MutableStateFlow(SyncTransport.WIFI_COMPLETED_RECORDINGS)
    val syncTransport: StateFlow<SyncTransport> = _syncTransport
    private val _devices = MutableStateFlow<List<DiscoveredRecorder>>(emptyList())
    val devices: StateFlow<List<DiscoveredRecorder>> = _devices
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning
    private val _scanError = MutableStateFlow<String?>(null)
    val scanError: StateFlow<String?> = _scanError
    private val _connectionError = MutableStateFlow<String?>(null)
    val connectionError: StateFlow<String?> = _connectionError
    private val _boundDeviceId = MutableStateFlow<String?>(null)
    private val _managedDevice = MutableStateFlow<DeviceEntity?>(null)
    val managedDevice: StateFlow<DeviceEntity?> = _managedDevice
    private val _phoneTestRecording = MutableStateFlow(PhoneTestRecordingState())
    val phoneTestRecording: StateFlow<PhoneTestRecordingState> = _phoneTestRecording
    private val _recorderControl = MutableStateFlow<RecorderControlAction>(RecorderControlAction.Idle)
    val recorderControl: StateFlow<RecorderControlAction> = _recorderControl
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice
    private var scanJob: Job? = null
    private var syncPreparationJob: Job? = null
    private val livePreviewSaveMutex = Mutex()
    private var livePreviewWasActive = false

    init {
        viewModelScope.launch {
            app.container.recordings.enforceLocalCache()
            app.container.recordings.migrateLegacyGeneratedTitles()
            app.container.recordings.migrateRecorderFileTimesFromUtcNames()
            app.container.recordings.unqueuedBleLivePreviews().forEach { recording ->
                app.container.recordings.markProcessing(recording.id, ProcessingState.QUEUED)
                app.container.processingScheduler.schedule(recording.id)
            }
            _managedDevice.value = app.container.database.deviceDao().mostRecentlySeen()
            _boundDeviceId.value = _managedDevice.value?.id
        }
        viewModelScope.launch {
            livePreview.collect { state ->
                if (state.isActive) {
                    livePreviewWasActive = true
                } else if (livePreviewWasActive) {
                    livePreviewWasActive = false
                    runCatching { saveLivePreviewIfNeeded() }
                        .onFailure { reportRecorderCommandFailure("Bluetooth live save", it) }
                }
            }
        }
    }

    fun scan() {
        scanJob?.cancel()
        _devices.value = emptyList()
        _scanError.value = null
        _connectionError.value = null
        _isScanning.value = true
        scanJob = viewModelScope.launch {
            try {
                withTimeoutOrNull(SCAN_DURATION_MS) {
                    ble.scan().collect { discovered ->
                        _devices.value = (_devices.value.filterNot { it.address == discovered.address } + discovered)
                            .sortedWith(compareByDescending<DiscoveredRecorder> { it.likelyRecorder }.thenByDescending { it.rssi })
                    }
                }
            } catch (failure: Exception) {
                _scanError.value = failure.message ?: "Bluetooth scanning failed"
            } finally {
                _isScanning.value = false
            }
        }
    }

    fun connect(address: String) = viewModelScope.launch {
        stopScan()
        syncSession.cancelRecovery()
        // A new BLE session is a new user attempt. Do not leave a previous
        // terminal sync result or a pending record-control affordance on screen.
        sync.clearOutcome()
        _recorderControl.value = RecorderControlAction.Idle
        _connectionError.value = null
        runCatching {
            ble.connect(address)
            ble.setTime(System.currentTimeMillis() / 1_000)
            ble.getStatus()
            val deviceDao = app.container.database.deviceDao()
            val device = deviceDao.find(address)?.copy(lastSeenAt = System.currentTimeMillis()) ?: DeviceEntity(
                id = address,
                displayName = _devices.value.firstOrNull { it.address == address }?.name ?: address,
                lastSeenAt = System.currentTimeMillis()
            )
            deviceDao.upsert(device)
            _boundDeviceId.value = address
            _managedDevice.value = device
            syncSession.markConnected()
        }.onFailure {
            _connectionError.value = "Couldn't connect to the recorder. Keep it nearby, then try again."
            ble.disconnect()
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _isScanning.value = false
    }

    fun startRecording() {
        if (!canControlRecorder()) return
        _recorderControl.value = RecorderControlAction.Starting
        viewModelScope.launch {
            try {
                runCatching { ble.startRecording(RecorderBleProtocol.Scene.NORMAL) }
                    .onFailure { reportRecorderCommandFailure("Start recording", it) }
            } finally {
                _recorderControl.value = RecorderControlAction.Idle
            }
        }
    }

    fun stopRecording() {
        if (!canControlRecorder()) return
        _recorderControl.value = RecorderControlAction.Stopping
        viewModelScope.launch {
            try {
                runCatching {
                    if (liveStreamRecorder.isSaving) stopAndSaveLivePreview()
                    ble.stopRecording()
                }
                    .onFailure { reportRecorderCommandFailure("Stop recording", it) }
            } finally {
                _recorderControl.value = RecorderControlAction.Idle
            }
        }
    }

    private fun canControlRecorder(): Boolean {
        if (_recorderControl.value != RecorderControlAction.Idle) {
            _notice.value = "Recorder command is already in progress"
            return false
        }
        if (sync.state.value.isRunning) {
            _notice.value = "Finish recorder sync before trying to record"
            return false
        }
        if (ble.connection.value !is BleConnectionState.Connected) {
            _notice.value = "Connect the recorder before trying to record"
            return false
        }
        return true
    }

    private fun reportRecorderCommandFailure(action: String, error: Throwable) {
        val detail = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
        _notice.value = "$action failed: $detail"
    }

    fun setLivePreview(enabled: Boolean) {
        if (enabled) {
            if (!canControlLivePreview()) return
            if (ble.livePreview.value.isActive) return
            viewModelScope.launch {
                try {
                    liveStreamRecorder.start(viewModelScope)
                    ble.startLivePreview()
                } catch (error: Throwable) {
                    discardLivePreview()
                    reportRecorderCommandFailure("Live preview", error)
                }
            }
        } else if (ble.livePreview.value.isActive) {
            viewModelScope.launch {
                runCatching { stopAndSaveLivePreview() }
                    .onFailure { reportRecorderCommandFailure("Live preview", it) }
            }
        }
    }

    fun selectSyncTransport(transport: SyncTransport) {
        if (sync.state.value.isRunning && transport != SyncTransport.WIFI_COMPLETED_RECORDINGS) {
            _notice.value = "Finish recorder sync before switching transfer method"
            return
        }
        if (_syncTransport.value != transport) sync.clearOutcome()
        _syncTransport.value = transport
    }

    private fun canControlLivePreview(): Boolean {
        if (_recorderControl.value != RecorderControlAction.Idle) {
            _notice.value = "Finish the recording command before starting live preview"
            return false
        }
        if (sync.state.value.isRunning) {
            _notice.value = "Finish recorder sync before starting live preview"
            return false
        }
        if (ble.connection.value !is BleConnectionState.Connected) {
            _notice.value = "Connect the recorder before starting live preview"
            return false
        }
        if (ble.status.value?.recording != 1) {
            _notice.value = "Start recording before starting live preview"
            return false
        }
        if (liveStreamRecorder.isSaving) {
            _notice.value = "Saving the previous Bluetooth live preview"
            return false
        }
        return true
    }

    private suspend fun stopAndSaveLivePreview() {
        try {
            if (ble.livePreview.value.isActive) ble.stopLivePreview()
        } finally {
            saveLivePreviewIfNeeded()
        }
    }

    private suspend fun saveLivePreviewIfNeeded() = livePreviewSaveMutex.withLock {
        if (!liveStreamRecorder.isSaving) return@withLock
        val savedPreview = withContext(Dispatchers.IO) {
            liveStreamRecorder.stop(_boundDeviceId.value, status.value?.scene)
        }
        savedPreview?.let { saved ->
            app.container.recordings.markProcessing(saved.recording.id, ProcessingState.QUEUED)
            app.container.processingScheduler.schedule(saved.recording.id)
            _notice.value = if (saved.publicUri != null) {
                "Bluetooth recording saved and queued for transcription"
            } else {
                "Bluetooth recording queued for transcription; public copy failed: ${saved.publicExportError}"
            }
        }
    }

    private suspend fun discardLivePreview() = livePreviewSaveMutex.withLock {
        if (liveStreamRecorder.isSaving) liveStreamRecorder.discard()
    }

    fun disconnect() {
        syncSession.cancelRecovery()
        _connectionError.value = null
        _recorderControl.value = RecorderControlAction.Idle
        viewModelScope.launch {
            runCatching {
                if (liveStreamRecorder.isSaving) stopAndSaveLivePreview()
            }
            ble.disconnect()
        }
    }
    fun renameDevice(name: String) = viewModelScope.launch {
        val device = _managedDevice.value ?: return@launch
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@launch
        app.container.database.deviceDao().rename(device.id, trimmed)
        _managedDevice.value = device.copy(displayName = trimmed)
    }

    fun sync(): Boolean {
        if (syncPreparationJob?.isActive == true || syncSession.isSyncActive) return false
        if (ble.connection.value !is BleConnectionState.Connected) {
            _notice.value = "Connect the recorder before synchronizing"
            return false
        }
        if (sync.state.value.isRunning) {
            _notice.value = "Recorder sync is already in progress"
            return false
        }
        _recorderControl.value = RecorderControlAction.Idle
        if (!liveStreamRecorder.isSaving) {
            return syncSession.start(_boundDeviceId.value, status.value?.scene)
        }
        syncPreparationJob = viewModelScope.launch {
            runCatching { stopAndSaveLivePreview() }
                .onSuccess { syncSession.start(_boundDeviceId.value, status.value?.scene) }
                .onFailure { reportRecorderCommandFailure("Prepare sync", it) }
        }
        return true
    }

    fun toggleBleLiveStream() {
        setLivePreview(!ble.livePreview.value.isActive)
    }

    fun cancelSync() {
        syncPreparationJob?.cancel()
        syncSession.cancel()
    }

    fun clearSyncOutcome() {
        sync.clearOutcome()
    }

    fun retry(recordingId: String) = app.container.processingScheduler.schedule(recordingId)
    fun preparePhoneTestRecording() {
        if (!_phoneTestRecording.value.isRecording) _phoneTestRecording.value = PhoneTestRecordingState()
    }

    fun startPhoneTestRecording() {
        runCatching { phoneTestRecorder.start() }
            .onSuccess { _phoneTestRecording.value = PhoneTestRecordingState(isRecording = true) }
            .onFailure { _phoneTestRecording.value = PhoneTestRecordingState(error = it.message ?: "Unable to start the microphone") }
    }

    fun stopPhoneTestRecording() = viewModelScope.launch {
        val capture = runCatching { phoneTestRecorder.stop() }.getOrElse {
            _phoneTestRecording.value = PhoneTestRecordingState(error = it.message ?: "Unable to save the phone recording")
            return@launch
        }
        runCatching {
            val recording = app.container.recordings.importPhoneTestRecording(capture.file, capture.durationMillis)
            app.container.processingScheduler.schedule(recording.id)
        }.onSuccess {
            _phoneTestRecording.value = PhoneTestRecordingState(isComplete = true)
        }.onFailure {
            capture.file.delete()
            _phoneTestRecording.value = PhoneTestRecordingState(error = it.message ?: "Unable to add the phone recording")
        }
    }

    fun cancelPhoneTestRecording() {
        phoneTestRecorder.cancel()
        _phoneTestRecording.value = PhoneTestRecordingState()
    }
    fun delete(recordingId: String, onDeleted: () -> Unit = {}) = viewModelScope.launch {
        app.container.processingScheduler.cancel(recordingId)
        app.container.recordings.delete(recordingId)
        _notice.value = "Recording successfully deleted"
        onDeleted()
    }
    fun clearNotice() { _notice.value = null }
    fun rename(recordingId: String, title: String) = viewModelScope.launch { app.container.recordings.rename(recordingId, title) }
    fun setActionItemCompleted(actionId: String, completed: Boolean) = viewModelScope.launch { app.container.recordings.setActionItemCompleted(actionId, completed) }
    fun detail(id: String): StateFlow<RecordingDetail> {
        viewModelScope.launch {
            app.container.recordings.find(id) ?: return@launch
            app.container.recordings.touch(id)
        }
        return app.container.recordings.observeDetail(id).stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            RecordingDetail(null, emptyList(), null, emptyList())
        )
    }

    override fun onCleared() {
        phoneTestRecorder.cancel()
        liveStreamRecorder.abandon()
        super.onCleared()
    }

    private companion object { const val SCAN_DURATION_MS = 20_000L }
}

data class PhoneTestRecordingState(
    val isRecording: Boolean = false,
    val isComplete: Boolean = false,
    val error: String? = null
)

sealed interface RecorderControlAction {
    data object Idle : RecorderControlAction
    data object Starting : RecorderControlAction
    data object Stopping : RecorderControlAction
}

enum class SyncTransport {
    WIFI_COMPLETED_RECORDINGS,
    BLUETOOTH_LIVE_STREAM
}

@Composable
private fun RecorderApp(viewModel: MainViewModel) {
    val context = LocalContext.current
    val requiredPermissions = remember {
        buildList {
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }.toTypedArray()
    }
    var permissionResultVersion by remember { mutableStateOf(0) }
    var permissionsRequested by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionsRequested = true
        permissionResultVersion += 1
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionResultVersion += 1
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val granted = permissionResultVersion.let {
        requiredPermissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }
    if (!granted) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("Allow device access", style = MaterialTheme.typography.headlineSmall)
            Text("Bluetooth and nearby WiFi permissions are needed to connect and synchronize the recorder.", modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))
            if (permissionsRequested) {
                Button(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = android.net.Uri.parse("package:${context.packageName}")
                        }
                    )
                }) { Text("Open app settings") }
            } else {
                Button(onClick = { launcher.launch(requiredPermissions) }) { Text("Continue") }
            }
        }
        return
    }
    val nav = rememberNavController()
    Scaffold(
        bottomBar = { AppNavigation(nav) }
    ) { padding ->
        NavHost(nav, startDestination = "library", modifier = Modifier.padding(padding)) {
            composable("library") { LibraryScreen(viewModel, nav) }
            composable("sync") { SyncCenterScreen(viewModel, nav) }
            composable("device") { DeviceScreen(viewModel, nav) }
            composable("detail/{id}") { backStack -> DetailScreen(viewModel, nav, backStack.arguments?.getString("id").orEmpty()) }
        }
    }
}

@Composable
private fun AppNavigation(nav: NavHostController) {
    val backStackEntry by nav.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    NavigationBar {
        NavigationBarItem(
            selected = currentRoute == "library",
            onClick = { nav.navigate("library") },
            icon = { Icon(Icons.AutoMirrored.Filled.List, null) },
            label = { Text("Library") }
        )
        NavigationBarItem(
            selected = currentRoute == "sync",
            onClick = { nav.navigate("sync") },
            icon = { Icon(Icons.Filled.Sync, null) },
            label = { Text("Sync") }
        )
        NavigationBarItem(
            selected = currentRoute == "device",
            onClick = { nav.navigate("device") },
            icon = { Icon(Icons.Filled.Bluetooth, null) },
            label = { Text("Device") }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(viewModel: MainViewModel, nav: NavHostController) {
    val recordings by viewModel.library.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    val recorderRecovery by viewModel.recorderRecovery.collectAsStateWithLifecycle()
    val recorderControl by viewModel.recorderControl.collectAsStateWithLifecycle()
    val livePreview by viewModel.livePreview.collectAsStateWithLifecycle()
    val phoneTestState by viewModel.phoneTestRecording.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val recorderControlsEnabled = connection is BleConnectionState.Connected &&
        !syncState.isRunning && recorderControl is RecorderControlAction.Idle
    var showPhoneTest by remember { mutableStateOf(false) }
    var recordingPendingDelete by remember { mutableStateOf<RecordingEntity?>(null) }
    val context = LocalContext.current
    val snackbarHost = remember { SnackbarHostState() }
    LaunchedEffect(notice) {
        notice?.let {
            snackbarHost.showSnackbar(it)
            viewModel.clearNotice()
        }
    }
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            viewModel.preparePhoneTestRecording()
            showPhoneTest = true
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
        TopAppBar(
            title = { Text("Recordings") },
            actions = {
                IconButton(onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        viewModel.preparePhoneTestRecording()
                        showPhoneTest = true
                    } else {
                        microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }) {
                    Icon(Icons.Filled.Mic, "Record a phone test")
                }
            }
        )
        }
    ) { padding ->
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(padding)) {
            item { SyncProgressPanel(syncState, onOpen = { nav.navigate("sync") }) }
            if (recorderRecovery !is RecorderRecoveryState.Idle) {
                item { RecorderRecoverySummary(recorderRecovery, onOpen = { nav.navigate("device") }) }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (status != null) {
                        when (recorderControl) {
                            RecorderControlAction.Starting -> {
                                OutlinedButton(onClick = {}, enabled = false) {
                                    CircularProgressIndicator(modifier = Modifier.width(18.dp))
                                    Text("Starting...")
                                }
                            }
                            RecorderControlAction.Stopping -> {
                                OutlinedButton(onClick = {}, enabled = false) {
                                    CircularProgressIndicator(modifier = Modifier.width(18.dp))
                                    Text("Stopping...")
                                }
                            }
                            RecorderControlAction.Idle -> if (status!!.recording == 0) {
                                OutlinedButton(onClick = viewModel::startRecording, enabled = recorderControlsEnabled) { Text("Start") }
                            } else {
                                OutlinedButton(onClick = viewModel::stopRecording, enabled = recorderControlsEnabled) { Text("Stop") }
                            }
                        }
                    }
                }
            }
            if (status != null && !recorderControlsEnabled && recorderControl is RecorderControlAction.Idle) {
                item {
                    Text(
                        if (syncState.isRunning) "Recorder controls are disabled while audio is syncing." else "Reconnect the recorder to control recording.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            if (status?.recording == 1) {
                item {
                    LivePreviewControl(
                        state = livePreview,
                        enabled = recorderControlsEnabled && livePreview !is LivePreviewState.Stopping,
                        onEnabledChange = viewModel::setLivePreview
                    )
                }
            }
            if (recordings.isEmpty()) {
                item {
                    Column(Modifier.padding(top = 72.dp), verticalArrangement = Arrangement.Center) {
                        Text("No recordings yet", style = MaterialTheme.typography.titleLarge)
                        Text("Record with your recorder or phone, then sync completed device recordings.", modifier = Modifier.padding(top = 8.dp))
                    }
                }
            } else {
                items(recordings, key = { it.id }) { recording ->
                    SwipeToDeleteRecordingRow(
                        recording = recording,
                        onOpen = { nav.navigate("detail/${recording.id}") },
                        onDelete = { recordingPendingDelete = recording }
                    )
                }
            }
        }
    }
    if (showPhoneTest) {
        PhoneTestRecordingDialog(
            state = phoneTestState,
            onStart = viewModel::startPhoneTestRecording,
            onStop = viewModel::stopPhoneTestRecording,
            onDismiss = {
                if (phoneTestState.isRecording) viewModel.cancelPhoneTestRecording()
                showPhoneTest = false
            }
        )
    }
    recordingPendingDelete?.let { recording ->
        DeleteRecordingDialog(
            title = recording.title,
            onConfirm = {
                recordingPendingDelete = null
                viewModel.delete(recording.id)
            },
            onDismiss = { recordingPendingDelete = null }
        )
    }
}

@Composable
private fun LivePreviewControl(
    state: LivePreviewState,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit
) {
    val isSwitchOn = state.isActive
    ListItem(
        headlineContent = { Text("Live preview") },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    when (state) {
                        LivePreviewState.Off -> "Receive live audio while this recording is in progress."
                        LivePreviewState.Starting -> "Connecting to live audio..."
                        is LivePreviewState.Receiving -> "Receiving live audio - ${formatLivePreviewDuration(state.receivedDurationMillis)}"
                        LivePreviewState.Stopping -> "Stopping live audio..."
                        is LivePreviewState.Unavailable -> "Live preview is unavailable. Recording and sync are still available."
                    }
                )
                if (state is LivePreviewState.Starting || state is LivePreviewState.Stopping) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        trailingContent = {
            Switch(
                checked = isSwitchOn,
                onCheckedChange = onEnabledChange,
                enabled = enabled || state is LivePreviewState.Starting
            )
        }
    )
}

private fun formatLivePreviewDuration(durationMillis: Long): String {
    val totalSeconds = durationMillis / 1_000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

@Composable
private fun SyncProgressPanel(syncState: SyncState, onOpen: () -> Unit) {
    if (syncState is SyncState.Idle) return
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (syncState) {
                SyncState.PreparingRecorder -> Text("Preparing recorder", style = MaterialTheme.typography.titleMedium)
                is SyncState.JoiningRecorderWifi -> Text(
                    when (syncState.phase) {
                        com.airecorder.mvp.core.wifi.RecorderWifiJoinPhase.STARTING -> "Starting recorder Wi-Fi"
                        com.airecorder.mvp.core.wifi.RecorderWifiJoinPhase.CHECKING_SAVED_WIFI -> "Checking saved recorder Wi-Fi"
                        com.airecorder.mvp.core.wifi.RecorderWifiJoinPhase.WAITING_FOR_HOTSPOT -> "Waiting for recorder hotspot"
                        com.airecorder.mvp.core.wifi.RecorderWifiJoinPhase.REQUESTING_SYSTEM_APPROVAL -> "Waiting for Android Wi-Fi approval"
                        com.airecorder.mvp.core.wifi.RecorderWifiJoinPhase.WAITING_FOR_MANUAL_SELECTION -> "Select recorder Wi-Fi to continue"
                        com.airecorder.mvp.core.wifi.RecorderWifiJoinPhase.CONNECTED -> "Recorder Wi-Fi connected"
                    },
                    style = MaterialTheme.typography.titleMedium
                )
                is SyncState.ConnectingToRecorderFiles -> Text(
                    "Connecting to recordings (${syncState.attempt}/${syncState.maxAttempts})",
                    style = MaterialTheme.typography.titleMedium
                )
                SyncState.ListingFiles -> Text("Finding recordings", style = MaterialTheme.typography.titleMedium)
                is SyncState.Downloading -> {
                    val progress = if (syncState.overallBytes > 0L) {
                        (syncState.overallTransferred.toFloat() / syncState.overallBytes.toFloat()).coerceIn(0f, 1f)
                    } else 0f
                    Text(
                        if (syncState.retryAttempt == 0) {
                            "Downloading recording ${syncState.currentFile} of ${syncState.totalFiles}"
                        } else {
                            "Retrying recording ${syncState.currentFile} of ${syncState.totalFiles}"
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(syncState.fileName, style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text(
                        "${formatTransferBytes(syncState.overallTransferred)} / ${formatTransferBytes(syncState.overallBytes)} (${(progress * 100).roundToInt()}%)",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "${syncState.dataMode.lowercase().replaceFirstChar(Char::uppercase)} FTP",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                SyncState.LeavingRecorderWifi -> Text("Finishing sync", style = MaterialTheme.typography.titleMedium)
                SyncState.WaitingForInternet -> Text("Recordings saved; waiting for internet", style = MaterialTheme.typography.titleMedium)
                SyncState.WaitingForBleRecovery -> Text("Sync complete", style = MaterialTheme.typography.titleMedium)
                is SyncState.Complete -> Text(
                    when {
                        syncState.recoveredRecordingCount > 0 -> "Sync complete: recovered audio has brief gaps"
                        syncState.importedCount > 0 -> "Sync complete: ${syncState.importedCount} new recording(s) added"
                        syncState.alreadyOnPhoneCount > 0 -> "No new recordings: files are already on this phone"
                        else -> "No completed recordings found"
                    },
                    style = MaterialTheme.typography.titleMedium
                )
                SyncState.Cancelled -> Text("Sync cancelled", style = MaterialTheme.typography.titleMedium)
                is SyncState.Failed -> Text("Sync needs attention. Tap for help.", color = MaterialTheme.colorScheme.error)
                SyncState.Idle -> Unit
            }
            if (syncState !is SyncState.Failed && syncState !is SyncState.Complete && syncState !is SyncState.Cancelled) {
                Text("Tap to view sync progress", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun RecorderRecoverySummary(state: RecorderRecoveryState, onOpen: () -> Unit) {
    if (state is RecorderRecoveryState.Idle) return
    Card(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = {
                Text(
                    when (state) {
                        RecorderRecoveryState.Idle -> ""
                        is RecorderRecoveryState.Reconnecting -> "Restoring recorder Bluetooth"
                        RecorderRecoveryState.Connected -> "Recorder reconnected"
                        RecorderRecoveryState.NeedsUserAction -> "Recorder needs reconnection"
                    }
                )
            },
            supportingContent = {
                Text(
                    when (state) {
                        RecorderRecoveryState.Idle -> ""
                        is RecorderRecoveryState.Reconnecting -> "Attempt ${state.attempt} of ${state.maxAttempts}"
                        RecorderRecoveryState.Connected -> "Recording controls are ready."
                        RecorderRecoveryState.NeedsUserAction -> "Power-cycle the recorder, then reconnect it."
                    }
                )
            },
            trailingContent = {
                if (state is RecorderRecoveryState.NeedsUserAction) {
                    OutlinedButton(onClick = onOpen) { Text("Connect") }
                }
            }
        )
    }
}

fun formatTransferBytes(bytes: Long): String = when {
    bytes >= 1_000_000L -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000L -> "%.1f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}

@Composable
private fun DeleteRecordingDialog(title: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete recording?") },
        text = { Text("Delete \"$title\" from this phone?") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun PhoneTestRecordingDialog(
    state: PhoneTestRecordingState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Phone test recording") },
        text = {
            when {
                state.isRecording -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Text("Recording from this phone")
                }
                state.isComplete -> Text("Saved to Recordings. Transcription is running in the background.")
                state.error != null -> Text(state.error, color = MaterialTheme.colorScheme.error)
                else -> Text("Record a short sample to test transcription without the recorder board.")
            }
        },
        confirmButton = {
            if (state.isRecording) {
                Button(onClick = onStop) { Icon(Icons.Filled.Stop, null); Text("Stop and transcribe") }
            } else if (!state.isComplete) {
                Button(onClick = onStart) { Icon(Icons.Filled.Mic, null); Text("Start recording") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (state.isRecording) "Discard" else "Close") } }
    )
}

@Composable
private fun SwipeToDeleteRecordingRow(recording: RecordingEntity, onOpen: () -> Unit, onDelete: () -> Unit) {
    val revealWidth = with(LocalDensity.current) { 96.dp.toPx() }
    var offsetX by remember(recording.id) { mutableFloatStateOf(0f) }
    val dragState = rememberDraggableState { delta ->
        offsetX = (offsetX + delta).coerceIn(-revealWidth, 0f)
    }
    Box(Modifier.fillMaxWidth().clipToBounds()) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(96.dp)
                .background(MaterialTheme.colorScheme.error)
                .clickable(onClick = onDelete),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.onError)
                Text("Delete", color = MaterialTheme.colorScheme.onError, style = MaterialTheme.typography.labelLarge)
            }
        }
        Card(
            Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .draggable(
                    state = dragState,
                    orientation = Orientation.Horizontal,
                    onDragStopped = { offsetX = if (offsetX <= -revealWidth / 2) -revealWidth else 0f }
                )
                .clickable {
                    if (offsetX < 0f) offsetX = 0f else onOpen()
                }
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(recording.title, fontWeight = FontWeight.SemiBold)
                Text(DateFormat.getDateTimeInstance().format(Date(recording.createdAt)), style = MaterialTheme.typography.bodySmall)
                Text(recording.processingState.name.replace('_', ' ').lowercase(), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceScreen(viewModel: MainViewModel, nav: NavHostController) {
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val managedDevice by viewModel.managedDevice.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val scanError by viewModel.scanError.collectAsStateWithLifecycle()
    val connectionError by viewModel.connectionError.collectAsStateWithLifecycle()
    var showDevicePicker by remember { mutableStateOf(false) }
    var deviceName by remember(managedDevice?.id, managedDevice?.displayName) { mutableStateOf(managedDevice?.displayName.orEmpty()) }
    val syncRunning = syncState.isRunning
    Scaffold(topBar = { TopAppBar(title = { Text("Device") }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showDevicePicker = true; viewModel.scan() }) { Icon(Icons.Filled.Bluetooth, null); Text("Find device") }
                    Button(
                        onClick = { if (viewModel.sync()) nav.navigate("sync") },
                        enabled = connection is BleConnectionState.Connected && !syncRunning
                    ) { Icon(Icons.Filled.Sync, null); Text("Sync") }
                }
            }
            if (connection !is BleConnectionState.Connected && !syncRunning) {
                item {
                    Text(
                        "Connect the recorder to sync saved recordings.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            item {
                OutlinedButton(onClick = viewModel::disconnect, enabled = connection !is BleConnectionState.Disconnected) { Icon(Icons.Filled.Bluetooth, null); Text("Disconnect") }
            }
            item {
                ListItem(
                    leadingContent = {
                        if (connection is BleConnectionState.Connecting) {
                            CircularProgressIndicator()
                        } else {
                            Icon(Icons.Filled.Bluetooth, contentDescription = null)
                        }
                    },
                    headlineContent = { Text("Connection: ${connection.label()}") },
                    supportingContent = {
                        Text(
                            when (connection) {
                                BleConnectionState.Connecting -> "Connecting to the selected recorder..."
                                BleConnectionState.Connected -> "Recorder is ready for recording and sync."
                                is BleConnectionState.Failed -> "Connection failed. Select the recorder and try again."
                                BleConnectionState.Disconnected -> "No recorder is connected."
                            }
                        )
                    }
                )
                if (connection is BleConnectionState.Connecting) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                connectionError?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
            item {
                if (managedDevice == null) {
                    Text("No recorder selected", style = MaterialTheme.typography.titleMedium)
                } else {
                    OutlinedTextField(
                        value = deviceName,
                        onValueChange = { deviceName = it },
                        label = { Text("Device name") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { viewModel.renameDevice(deviceName) },
                        enabled = deviceName.trim().isNotEmpty() && deviceName.trim() != managedDevice!!.displayName,
                        modifier = Modifier.padding(top = 8.dp)
                    ) { Text("Save name") }
                    Text("Bluetooth address: ${managedDevice!!.id}", modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium)
                    Text("Last seen: ${DateFormat.getDateTimeInstance().format(Date(managedDevice!!.lastSeenAt))}", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (syncState !is SyncState.Idle) {
                item { SyncProgressPanel(syncState, onOpen = { nav.navigate("sync") }) }
            }
            if (syncState is SyncState.JoiningRecorderWifi) {
                item {
                    ListItem(
                        headlineContent = { Text("Recorder Wi-Fi needs attention") },
                        supportingContent = { Text("Open Sync for the next step and connection help.") },
                        trailingContent = {
                            OutlinedButton(onClick = { nav.navigate("sync") }) {
                                Text("View sync")
                            }
                        }
                    )
                }
            }
            if (status != null) {
                item { ListItem(headlineContent = { Text("Battery") }, supportingContent = { Text("${estimatedBatteryPercent(status!!.batteryMillivolts)}% estimate  |  ${status!!.batteryMillivolts} mV") }) }
                item { ListItem(headlineContent = { Text("Storage") }, supportingContent = { Text("${status!!.freePercent}% free") }) }
                item { ListItem(headlineContent = { Text("Recording") }, supportingContent = { Text(recordingStatusLabel(status!!.recording, status!!.recordingSeconds)) }) }
                item { ListItem(headlineContent = { Text("Device time") }, supportingContent = { Text(if (status!!.timeSynced) "Synchronized" else "Not synchronized") }) }
            } else {
                item { Text("Device status is available after a Bluetooth connection is established.", style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
    if (showDevicePicker) {
        NearbyRecorderDialog(
            devices = devices,
            isScanning = isScanning,
            error = scanError,
            onSelect = { address -> viewModel.connect(address); showDevicePicker = false },
            onScanAgain = viewModel::scan,
            onDismiss = { viewModel.stopScan(); showDevicePicker = false }
        )
    }
}

private fun estimatedBatteryPercent(millivolts: Int): Int = ((millivolts - 3_300) * 100 / 900).coerceIn(0, 100)

private fun recordingStatusLabel(recording: Int, seconds: Long): String = when (recording) {
    1 -> "Recording for ${seconds}s"
    2 -> "Paused"
    else -> "Idle"
}

@Composable
private fun NearbyRecorderDialog(
    devices: List<DiscoveredRecorder>,
    isScanning: Boolean,
    error: String?,
    onSelect: (String) -> Unit,
    onScanAgain: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nearby Bluetooth devices") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isScanning) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator()
                    Text("Scanning nearby Bluetooth devices")
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!isScanning && devices.isEmpty() && error == null) {
                    Text("No Bluetooth advertisements were received. Make sure the recorder is not in Wi-Fi transfer mode, power-cycle it, then scan again. If nRF Connect on this same phone also cannot see NSRecorder, the recorder is not advertising BLE.")
                }
                devices.forEach { device ->
                    ListItem(
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(device.address) },
                        leadingContent = { Icon(Icons.Filled.Bluetooth, null) },
                        headlineContent = {
                            Text(if (device.likelyRecorder) device.name else "${device.name} (unverified)")
                        },
                        supportingContent = { Text("${signalLabel(device.rssi)} signal  |  ${device.address}") }
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onScanAgain, enabled = !isScanning) { Text("Scan again") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

private fun signalLabel(rssi: Int): String = when {
    rssi >= -60 -> "Strong"
    rssi >= -75 -> "Nearby"
    else -> "Weak"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailScreen(viewModel: MainViewModel, nav: NavHostController, recordingId: String) {
    val detailFlow = remember(recordingId) { viewModel.detail(recordingId) }
    val detail by detailFlow.collectAsState()
    val context = LocalContext.current
    val recording = detail.recording
    var title by remember(recording?.id, recording?.title) { mutableStateOf(recording?.title.orEmpty()) }
    var isEditingTitle by remember(recording?.id) { mutableStateOf(false) }
    var titleFieldWasFocused by remember(recording?.id) { mutableStateOf(false) }
    var showDeleteDialog by remember(recording?.id) { mutableStateOf(false) }
    val titleFocusRequester = remember(recording?.id) { FocusRequester() }

    fun saveTitle() {
        val updatedTitle = title.trim()
        if (recording != null && updatedTitle.isNotEmpty() && updatedTitle != recording.title) {
            viewModel.rename(recording.id, updatedTitle)
        }
        isEditingTitle = false
    }

    LaunchedEffect(isEditingTitle) {
        if (isEditingTitle) titleFocusRequester.requestFocus()
    }

    Scaffold(topBar = {
        TopAppBar(
            title = {
                if (recording != null && isEditingTitle) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(titleFocusRequester)
                            .onFocusChanged { focus ->
                                if (focus.isFocused) titleFieldWasFocused = true
                                if (titleFieldWasFocused && !focus.isFocused && isEditingTitle) saveTitle()
                            },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { saveTitle() })
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(recording?.title ?: "Recording")
                        if (recording != null) {
                            IconButton(onClick = { isEditingTitle = true }) {
                                Icon(Icons.Filled.Edit, "Edit recording title")
                            }
                        }
                    }
                }
            }
        )
    }) { padding ->
        val currentRecording = recording ?: return@Scaffold
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                val audioFile = currentRecording.rawAudioPath?.let(::File)?.takeIf(File::isFile)
                if (audioFile == null) {
                    Text("Audio was removed from the local cache. The transcript remains available.", style = MaterialTheme.typography.bodySmall)
                } else {
                    RecordingAudioPlayer(currentRecording, audioFile)
                }
                if (currentRecording.processingState == ProcessingState.FAILED) Button(onClick = { viewModel.retry(currentRecording.id) }, modifier = Modifier.padding(top = 8.dp)) { Text("Retry processing") }
            }
            item { detail.summary?.let { summary -> Column { Text(summary.title, style = MaterialTheme.typography.titleLarge); Text(summary.content) } } }
            item {
                if (detail.actionItems.isNotEmpty()) Column {
                    Text("Action items", style = MaterialTheme.typography.titleMedium)
                    detail.actionItems.forEach { action ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = action.completed, onCheckedChange = { viewModel.setActionItemCompleted(action.id, it) })
                            Text(action.text)
                        }
                    }
                }
            }
            item {
                if (detail.transcript.isNotEmpty()) Column { Text("Transcript", style = MaterialTheme.typography.titleMedium); detail.transcript.forEach { Text("${it.startMillis / 1000}s  ${it.text}", modifier = Modifier.padding(top = 8.dp)) } }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val share = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, detail.summary?.content ?: detail.transcript.joinToString("\n") { it.text }) }
                        context.startActivity(Intent.createChooser(share, "Share recording"))
                    }) { Text("Share") }
                    OutlinedButton(onClick = { showDeleteDialog = true }) { Text("Delete") }
                }
            }
        }
    }
    if (recording != null && showDeleteDialog) {
        DeleteRecordingDialog(
            title = recording.title,
            onConfirm = {
                showDeleteDialog = false
                viewModel.delete(recording.id) {
                    if (!nav.popBackStack()) nav.navigate("library")
                }
            },
            onDismiss = { showDeleteDialog = false }
        )
    }
}

@Composable
private fun RecordingAudioPlayer(recording: RecordingEntity, audioFile: File) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var player by remember(recording.id) { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember(recording.id) { mutableStateOf(false) }
    var preparing by remember(recording.id) { mutableStateOf(false) }
    var error by remember(recording.id) { mutableStateOf<String?>(null) }

    DisposableEffect(recording.id) {
        onDispose {
            player?.release()
            player = null
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            enabled = !preparing,
            onClick = {
                val active = player
                when {
                    active != null && playing -> {
                        active.pause()
                        playing = false
                    }
                    active != null -> {
                        if (active.currentPosition >= (active.duration - 200).coerceAtLeast(0)) active.seekTo(0)
                        active.start()
                        playing = true
                    }
                    else -> {
                        preparing = true
                        error = null
                        scope.launch {
                            val mediaPlayer = try {
                                val playable = withContext(Dispatchers.IO) {
                                    playableAudio(recording, audioFile, context.cacheDir)
                                }
                                MediaPlayer().apply {
                                    setDataSource(playable.absolutePath)
                                    setOnCompletionListener { playing = false }
                                    prepare()
                                }
                            } catch (failure: Exception) {
                                if (failure is CancellationException) throw failure
                                preparing = false
                                error = failure.message ?: "Couldn't play this recording"
                                return@launch
                            }
                            if (!isActive) {
                                mediaPlayer.release()
                                return@launch
                            }
                            player = mediaPlayer
                            mediaPlayer.start()
                            playing = true
                            preparing = false
                        }
                    }
                }
            }
        ) {
            Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = null)
            Text(when {
                preparing -> "Preparing audio"
                playing -> "Pause"
                else -> "Play"
            }, modifier = Modifier.padding(start = 8.dp))
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

private fun playableAudio(recording: RecordingEntity, source: File, cacheDir: File): File {
    if (recording.audioFormat == AudioFormat.OGG_OPUS) return source
    val decoded = File(cacheDir, "playback/${recording.id}-${source.length()}-${source.lastModified()}.wav")
    if (decoded.isFile && decoded.length() > 44) return decoded
    decoded.parentFile?.mkdirs()
    return ConcentusRawOpusDecoder().decodeToWav(source, decoded)
}

private fun BleConnectionState.label(): String = when (this) {
    BleConnectionState.Disconnected -> "Disconnected"
    BleConnectionState.Connecting -> "Connecting"
    BleConnectionState.Connected -> "Connected"
    is BleConnectionState.Failed -> "Unable to connect"
}
