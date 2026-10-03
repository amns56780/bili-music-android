package com.bilimusic.app.ui.login

import android.os.SystemClock
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bilimusic.app.data.remote.ApiResult
import com.bilimusic.app.data.repository.AuthRepository
import com.bilimusic.app.data.repository.QrPollOutcome
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** FR-1 扫码状态机的 4 种状态 + 加载/错误 */
enum class QrStatus {
    Loading,
    WaitingScan,
    ScannedNotConfirmed,
    Success,
    Expired,
    Error,
}

data class LoginUiState(
    val qrImage: ImageBitmap? = null,
    val status: QrStatus = QrStatus.Loading,
    val remainSeconds: Int = LoginViewModel.QR_TTL_SECONDS,
    val manualCookie: String = "",
    val showManualInput: Boolean = false,
    val showHowToGetCookie: Boolean = false,
    val submittingManual: Boolean = false,
    val message: String? = null,
    val errorMessage: String? = null,
) {
    val progress: Float
        get() = (remainSeconds.toFloat() / LoginViewModel.QR_TTL_SECONDS).coerceIn(0f, 1f)

    val statusText: String
        get() = when (status) {
            QrStatus.Loading -> "正在生成二维码…"
            QrStatus.WaitingScan -> "等待扫码：请用 B 站手机客户端扫描上方二维码"
            QrStatus.ScannedNotConfirmed -> "已扫码，请在手机上确认登录"
            QrStatus.Success -> "登录成功"
            QrStatus.Expired -> "二维码已过期，正在自动刷新…"
            QrStatus.Error -> errorMessage ?: "二维码获取失败"
        }
}

sealed interface LoginEvent {
    data class LoggedIn(val name: String) : LoginEvent
}

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    private val _events = Channel<LoginEvent>(Channel.BUFFERED)
    val events: Flow<LoginEvent> = _events.receiveAsFlow()

    private var pollJob: Job? = null
    private var tickerJob: Job? = null
    private var autoRefreshJob: Job? = null

    /** 二维码到期时刻（用 elapsedRealtime，不受用户改系统时间影响） */
    private var deadlineElapsedMs: Long = 0L

    init {
        viewModelScope.launch {
            authRepository.notice.collect { notice ->
                if (notice != null) {
                    _uiState.update { it.copy(message = notice) }
                    authRepository.consumeNotice()
                }
            }
        }
        startQrLogin()
    }

    /** 申请新二维码并开始轮询 + 倒计时 */
    fun startQrLogin() {
        cancelJobs()
        _uiState.update {
            it.copy(
                status = QrStatus.Loading,
                remainSeconds = QR_TTL_SECONDS,
                errorMessage = null,
                qrImage = null,
            )
        }
        viewModelScope.launch {
            when (val result = authRepository.createQrSession()) {
                is ApiResult.Success -> {
                    val session = result.data
                    val bitmap = withContext(Dispatchers.Default) {
                        QrCodeEncoder.encode(session.content)
                    }
                    deadlineElapsedMs = SystemClock.elapsedRealtime() + QR_TTL_SECONDS * 1000L
                    _uiState.update {
                        it.copy(
                            qrImage = bitmap,
                            status = QrStatus.WaitingScan,
                            remainSeconds = QR_TTL_SECONDS,
                            errorMessage = null,
                        )
                    }
                    startTicker()
                    startPolling(session.qrcodeKey)
                }

                is ApiResult.Error -> _uiState.update {
                    it.copy(status = QrStatus.Error, errorMessage = result.failure.userMessage)
                }
            }
        }
    }

    /** 每秒刷新倒计时；到点直接判定过期 */
    private fun startTicker() {
        tickerJob = viewModelScope.launch {
            while (isActive) {
                val remainMs = deadlineElapsedMs - SystemClock.elapsedRealtime()
                val remainSeconds = ((remainMs + 999L) / 1000L).coerceAtLeast(0L).toInt()
                _uiState.update { it.copy(remainSeconds = remainSeconds) }
                if (remainMs <= 0L) {
                    onQrExpired()
                    break
                }
                delay(250L)
            }
        }
    }

    /** 2 秒轮询一次；失败按指数退避，最多连续 5 次后停手并提示 */
    private fun startPolling(qrcodeKey: String) {
        pollJob = viewModelScope.launch {
            var intervalMs = POLL_INTERVAL_MS
            var consecutiveFailures = 0
            while (isActive) {
                if (SystemClock.elapsedRealtime() >= deadlineElapsedMs) return@launch
                when (val outcome = authRepository.pollQrSession(qrcodeKey)) {
                    QrPollOutcome.WaitingScan -> {
                        consecutiveFailures = 0
                        intervalMs = POLL_INTERVAL_MS
                        _uiState.update { it.copy(status = QrStatus.WaitingScan, errorMessage = null) }
                    }

                    QrPollOutcome.ScannedNotConfirmed -> {
                        consecutiveFailures = 0
                        intervalMs = POLL_INTERVAL_MS
                        _uiState.update {
                            it.copy(status = QrStatus.ScannedNotConfirmed, errorMessage = null)
                        }
                    }

                    is QrPollOutcome.Succeeded -> {
                        cancelJobs()
                        _uiState.update { it.copy(status = QrStatus.Success, errorMessage = null) }
                        _events.send(LoginEvent.LoggedIn(outcome.account.name))
                        return@launch
                    }

                    QrPollOutcome.Expired -> {
                        onQrExpired()
                        return@launch
                    }

                    is QrPollOutcome.Failed -> {
                        consecutiveFailures++
                        intervalMs = (POLL_INTERVAL_MS * (1L shl consecutiveFailures.coerceAtMost(3)))
                            .coerceAtMost(MAX_POLL_INTERVAL_MS)
                        if (consecutiveFailures >= 5) {
                            _uiState.update {
                                it.copy(
                                    status = QrStatus.Error,
                                    errorMessage = "扫码失败：${outcome.message}",
                                )
                            }
                            return@launch
                        }
                    }
                }
                delay(intervalMs)
            }
        }
    }

    private fun onQrExpired() {
        pollJob?.cancel()
        tickerJob?.cancel()
        if (_uiState.value.status == QrStatus.Success) return
        _uiState.update { it.copy(status = QrStatus.Expired, remainSeconds = 0) }
        // 过期后自动刷新新码
        autoRefreshJob?.cancel()
        autoRefreshJob = viewModelScope.launch {
            delay(1500L)
            if (_uiState.value.status == QrStatus.Expired) startQrLogin()
        }
    }

    // ---------------- 手动粘贴 Cookie（保底路径） ----------------

    fun onManualCookieChange(value: String) {
        _uiState.update { it.copy(manualCookie = value) }
    }

    fun toggleManualInput() {
        _uiState.update { it.copy(showManualInput = !it.showManualInput) }
    }

    fun toggleHowToGetCookie() {
        _uiState.update {
            it.copy(
                showHowToGetCookie = !it.showHowToGetCookie,
                showManualInput = if (!it.showManualInput) true else it.showManualInput,
            )
        }
    }

    fun submitManualCookie() {
        val raw = _uiState.value.manualCookie
        if (raw.isBlank()) {
            _uiState.update { it.copy(message = "请先粘贴 Cookie 再点登录") }
            return
        }
        if (_uiState.value.submittingManual) return
        viewModelScope.launch {
            _uiState.update { it.copy(submittingManual = true, errorMessage = null) }
            when (val result = authRepository.loginWithRawCookie(raw)) {
                is ApiResult.Success -> {
                    cancelJobs()
                    _uiState.update {
                        it.copy(status = QrStatus.Success, submittingManual = false)
                    }
                    _events.send(LoginEvent.LoggedIn(result.data.name))
                }

                is ApiResult.Error -> _uiState.update {
                    it.copy(submittingManual = false, message = result.failure.userMessage)
                }
            }
        }
    }

    fun refreshQr() = startQrLogin()

    fun dismissMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private fun cancelJobs() {
        pollJob?.cancel()
        tickerJob?.cancel()
        autoRefreshJob?.cancel()
    }

    override fun onCleared() {
        cancelJobs()
        super.onCleared()
    }

    companion object {
        /** 二维码有效期 180 秒 */
        const val QR_TTL_SECONDS = 180

        private const val POLL_INTERVAL_MS = 2_000L
        private const val MAX_POLL_INTERVAL_MS = 16_000L
    }
}
