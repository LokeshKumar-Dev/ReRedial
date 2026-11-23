package com.example.redial

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

data class HomeUiState(
    val delay: String = "5",
    val tries: String = "1",
    val phoneNumber: String = "",
    val tryCount: Int = 0,
    val countdownSeconds: Int = 0,
    val isRunning: Boolean = false,
) {
    val delaySeconds: Int?
        get() = delay.toIntOrNull()

    val totalTries: Int?
        get() = tries.toIntOrNull()

    val canStart: Boolean
        get() {
            val delayValue = delaySeconds
            val triesValue = totalTries
            return !isRunning &&
                    delayValue != null &&
                    delayValue >= 0 &&
                    triesValue != null &&
                    triesValue > 0 &&
                    phoneNumber.length == 10
        }

    val canResume: Boolean
        get() {
            val delayValue = delaySeconds
            val triesValue = totalTries
            return isRunning &&
                    (tryCount < (tries.toIntOrNull() ?: 0)) &&
                    delayValue != null &&
                    delayValue >= 0 &&
                    triesValue != null &&
                    triesValue > 0 &&
                    phoneNumber.length == 10
        }
}

class MainViewModel : ViewModel() {
    var homeUiState by mutableStateOf(HomeUiState())
        private set

    private val callPermissionState = MutableLiveData(-1)

    fun setCallPermissionState(state: PermissionState) {
        callPermissionState.value = state.ordinal
    }

    fun getCallPermissionLiveData(): MutableLiveData<Int> = callPermissionState

    @OptIn(ExperimentalStdlibApi::class)
    fun getCallPermissionState(): PermissionState {
        val state = callPermissionState.value ?: return PermissionState.NONE
        return PermissionState.entries.getOrNull(state) ?: PermissionState.NONE
    }

    fun updateDelay(value: String) {
        if (!homeUiState.isRunning) {
            homeUiState = homeUiState.copy(delay = value)
        }
    }

    fun updateTotalTries(value: String) {
        if (!homeUiState.isRunning) {
            homeUiState = homeUiState.copy(tries = value)
        }
    }

    fun updatePhoneNumber(value: String) {
        if (!homeUiState.isRunning) {
            homeUiState = homeUiState.copy(phoneNumber = value)
        }
    }

    fun startRedial(): Boolean {
        if (!homeUiState.canStart) return false

        homeUiState = homeUiState.copy(
            tryCount = 0,
            countdownSeconds = homeUiState.delaySeconds ?: 0,
            isRunning = true
        )
        return true
    }

    fun updateCountdown(seconds: Int) {
        if (homeUiState.isRunning) {
            homeUiState = homeUiState.copy(countdownSeconds = seconds)
        }
    }

    fun markCallStarted() {
        if (homeUiState.isRunning) {
            homeUiState = homeUiState.copy(
                tryCount = homeUiState.tryCount + 1,
                countdownSeconds = 0
            )
        }
    }

    fun cancelRedial() {
        homeUiState = homeUiState.copy(
            countdownSeconds = 0,
            isRunning = false
        )
    }

    fun completeRedial() {
        homeUiState = homeUiState.copy(
            countdownSeconds = 0,
            isRunning = false
        )
    }

    enum class PermissionState {
        NONE,
        GRANTED,
        DECLINED,
        SETTING_PAGE
    }
}