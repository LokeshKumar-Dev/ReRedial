package com.example.redial

import android.Manifest
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.net.toUri
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.autodroid.ui.theme.RedialTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var viewModel: MainViewModel
    private lateinit var telephonyManager: TelephonyManager
    private lateinit var clipboardManager: ClipboardManager
    private val callbackExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var isCallStateCallbackRegistered = false
    private var callWasActive = false
    private var countdownJob: Job? = null

    private val callStateCallback: TelephonyCallback = CallStateCallback { state ->
        lifecycleScope.launch {
            handleCallState(state)
        }
    }

    private val multiCallPermissionListener =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val callGranted = permissions[Manifest.permission.CALL_PHONE] == true
            val phoneStateGranted = permissions[Manifest.permission.READ_PHONE_STATE] == true

            updateCallPermissionState(callGranted && phoneStateGranted)
            if (!callGranted || !phoneStateGranted) {
                Toast.makeText(
                    this,
                    "Phone and call-state permissions are required",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel = ViewModelProvider.create(this)[MainViewModel::class]
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        setContent {
            RedialTheme {
                HomePage(
                    uiState = viewModel.homeUiState,
                    onDelayChanged = viewModel::updateDelay,
                    onTriesChanged = viewModel::updateTotalTries,
                    onPhoneNumberChanged = viewModel::updatePhoneNumber,
                    onStart = ::startRedialing,
                    onCancel = ::cancelRedialing,
                    onPaste = ::copyFromClipBoard
                )
            }
        }

        observeCallPermission()
    }

    override fun onResume() {
        super.onResume()
        startRedialing(true)
    }

    override fun onDestroy() {
        countdownJob?.cancel()
        unregisterCallStateCallback()
        callbackExecutor.shutdown()
        super.onDestroy()
    }

    private fun updateCallPermissionState(isGranted: Boolean) {
        val state = when {
            isGranted -> MainViewModel.PermissionState.GRANTED
            viewModel.getCallPermissionState() == MainViewModel.PermissionState.DECLINED ->
                MainViewModel.PermissionState.SETTING_PAGE
            else -> MainViewModel.PermissionState.DECLINED
        }
        viewModel.setCallPermissionState(state)
    }

    private fun checkAndShowCallPermission() {
        val permissions = arrayOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE
        )
        val allGranted = permissions.all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            viewModel.setCallPermissionState(MainViewModel.PermissionState.GRANTED)
        } else if (permissions.any(::shouldShowRequestPermissionRationale)) {
            viewModel.setCallPermissionState(MainViewModel.PermissionState.DECLINED)
        } else {
            multiCallPermissionListener.launch(permissions)
        }
    }

    private fun registerCallStateCallback() {
        if (isCallStateCallbackRegistered) return
        if (
            ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        telephonyManager.registerTelephonyCallback(callbackExecutor, callStateCallback)
        isCallStateCallbackRegistered = true
    }

    private fun unregisterCallStateCallback() {
        if (!isCallStateCallbackRegistered) return

        telephonyManager.unregisterTelephonyCallback(callStateCallback)
        isCallStateCallbackRegistered = false
    }

    private class CallStateCallback(
        private val onStateChanged: (state: Int) -> Unit
    ) : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            onStateChanged(state)
        }
    }

    private fun observeCallPermission() {
        viewModel.getCallPermissionLiveData().observe(this) {
            when (viewModel.getCallPermissionState()) {
                MainViewModel.PermissionState.GRANTED -> {}
                MainViewModel.PermissionState.SETTING_PAGE -> {
                    AlertDialog
                        .Builder(this)
                        .setTitle("Provide Permission")
                        .setMessage("Phone permissions are required to place calls and continue redialing.")
                        .setCancelable(false)
                        .setNeutralButton("Close") { _, _ ->
                            finishAffinity()
                        }
                        .setPositiveButton(
                            "Go to Settings"
                        ) { _, _ ->
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", packageName, null)
                            }
                            startActivity(intent)
                        }
                        .show()
                }

                MainViewModel.PermissionState.DECLINED -> {
                    try {
                        multiCallPermissionListener.launch(arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE))
                    } catch (e: Exception) {
                        checkAndShowCallPermission()
                    }
                }
                else -> {
                    checkAndShowCallPermission()
                }
            }
        }
    }

    private fun handleCallState(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING,
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                callWasActive = true
                Log.i("Phone", "Call active")
            }

            TelephonyManager.CALL_STATE_IDLE -> {
                Log.i("Phone", "Call idle")
                if (callWasActive) {
                    callWasActive = false
                    scheduleNextCall()
                }
            }
        }
    }

    private fun startRedialing(isResume: Boolean = false) {
        if (!hasRequiredPermissions()) {
            checkAndShowCallPermission()
            return
        }

        if ((isResume && viewModel.homeUiState.canResume) || (!isResume && viewModel.startRedial())) {
            registerCallStateCallback()
            scheduleNextCall()
        }
    }

    private fun cancelRedialing() {
        countdownJob?.cancel()
        countdownJob = null
        callWasActive = false
        unregisterCallStateCallback()
        viewModel.cancelRedial()
    }

    private fun completeRedialing() {
        countdownJob?.cancel()
        countdownJob = null
        callWasActive = false
        unregisterCallStateCallback()
        viewModel.completeRedial()
    }

    private fun copyFromClipBoard() {
        val clipboardText = clipboardManager.primaryClip
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
            .orEmpty()

        val digits = clipboardText.filter { it.isDigit() }
        val phoneNumber = when {
            digits.length == 10 -> digits
            digits.length > 10 -> digits.takeLast(10)
            else -> null
        }

        if (phoneNumber != null) {
            viewModel.updatePhoneNumber(phoneNumber)
        } else {
            Toast.makeText(
                this,
                "Clipboard doesn't contain a valid phone number",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun scheduleNextCall() {
        val state = viewModel.homeUiState
        val totalTries = state.totalTries ?: return completeRedialing()
        if (!state.isRunning) return
        if (state.tryCount >= totalTries) return completeRedialing()

        val delaySeconds = state.delaySeconds ?: return completeRedialing()
        countdownJob?.cancel()
        countdownJob = lifecycleScope.launch {
            for (seconds in delaySeconds downTo 1) {
                viewModel.updateCountdown(seconds)
                delay(1_000)
            }

            if (!viewModel.homeUiState.isRunning) return@launch
            viewModel.updateCountdown(0)
            placeCall(viewModel.homeUiState.phoneNumber)
        }
    }

    private fun placeCall(phoneNumber: String) {
        if (
            ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            cancelRedialing()
            checkAndShowCallPermission()
            return
        }

        val callIntent = Intent(Intent.ACTION_CALL).apply {
            data = "tel:+91$phoneNumber".toUri()
        }

        try {
            viewModel.markCallStarted()
            startActivity(callIntent)
        } catch (_: ActivityNotFoundException) {
            cancelRedialing()
            Toast.makeText(this, "No calling app is available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        return ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_PHONE_STATE
            ) == PackageManager.PERMISSION_GRANTED
    }
}

@Composable
private fun HomePage(
    uiState: HomeUiState,
    onDelayChanged: (String) -> Unit,
    onTriesChanged: (String) -> Unit,
    onPhoneNumberChanged: (String) -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onPaste: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 480.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Auto Redial",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Set the interval and number of call attempts",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(24.dp))

                    Row(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            modifier = Modifier.weight(1f),
                            value = uiState.delay,
                            onValueChange = { value ->
                                if (value.all { it.isDigit() }) {
                                    onDelayChanged(value)
                                }
                            },
                            enabled = !uiState.isRunning,
                            singleLine = true,
                            label = { Text("Delay (seconds)") },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number
                            )
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        OutlinedTextField(
                            modifier = Modifier.weight(1f),
                            value = uiState.tries,
                            onValueChange = { value ->
                                if (value.all { it.isDigit() }) {
                                    onTriesChanged(value)
                                }
                            },
                            enabled = !uiState.isRunning,
                            singleLine = true,
                            label = { Text("Total tries") },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row (modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(0.7f),
                            value = uiState.phoneNumber,
                            onValueChange = { value ->
                                if (value.length <= 10 && value.all { it.isDigit() }) {
                                    onPhoneNumberChanged(value)
                                }
                            },
                            enabled = !uiState.isRunning,
                            singleLine = true,
                            label = { Text("Phone number (+91)") },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Phone
                            )
                        )
                        Button(
                            modifier = Modifier.padding(vertical = 3.dp, horizontal = 4.dp),
                            onClick = onPaste,
                            colors = ButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.background,
                                disabledContentColor = MaterialTheme.colorScheme.background,
                                disabledContainerColor = MaterialTheme.colorScheme.secondaryContainer
                            )
                        ) {
                            Text("Paste")
                        }
                    }

                    RedialStatus(uiState)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 24.dp)
                    ) {
                        Button(
                            onClick = onStart,
                            enabled = uiState.canStart,
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = Color.White
                            )
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.phone_call),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = Color.White
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Start",
                                color = Color.White
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Button(
                            onClick = onCancel,
                            enabled = uiState.isRunning,
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = Color.White
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = Color.White
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Cancel",
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RedialStatus(uiState: HomeUiState) {
    if (!uiState.isRunning && uiState.tryCount == 0) return

    val totalTries = uiState.totalTries ?: 0
    val message = when {
        !uiState.isRunning -> "Calls placed: ${uiState.tryCount}"
        uiState.countdownSeconds > 0 -> {
            val nextAttempt = (uiState.tryCount + 1).coerceAtMost(totalTries)
            "Call $nextAttempt of $totalTries in ${uiState.countdownSeconds}s"
        }
        else -> "Call ${uiState.tryCount} of $totalTries in progress"
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.background,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HomePagePreview() {
    RedialTheme {
        HomePage(
            uiState = HomeUiState(
                delay = "5",
                tries = "3",
                phoneNumber = "9043093195",
                tryCount = 1,
                countdownSeconds = 4,
                isRunning = true
            ),
            onDelayChanged = {},
            onTriesChanged = {},
            onPhoneNumberChanged = {},
            onStart = {},
            onCancel = {},
            onPaste = {}
        )
    }
}