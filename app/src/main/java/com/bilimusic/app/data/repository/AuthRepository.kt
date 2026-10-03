package com.bilimusic.app.data.repository

import com.bilimusic.app.data.local.prefs.AccountStore
import com.bilimusic.app.data.remote.ApiResult
import com.bilimusic.app.data.remote.BiliApi
import com.bilimusic.app.data.remote.CookieStore
import com.bilimusic.app.data.remote.Failure
import com.bilimusic.app.data.remote.FailureKind
import com.bilimusic.app.data.remote.WbiSigner
import com.bilimusic.app.data.remote.apiCall
import com.bilimusic.app.domain.model.Account
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** 登录态三态：未知（启动校验中）/ 未登录 / 已登录 */
sealed interface LoginState {
    data object Unknown : LoginState

    data object LoggedOut : LoginState

    data class LoggedIn(val account: Account) : LoginState
}

/** 一次扫码会话：把内容画成二维码，key 用来轮询 */
data class QrSession(
    val qrcodeKey: String,
    val content: String,
)

/** 轮询结果（任务书 FR-1 的 4 种状态文案） */
sealed interface QrPollOutcome {
    /** 等待扫码 */
    data object WaitingScan : QrPollOutcome

    /** 已扫码，请在手机上确认 */
    data object ScannedNotConfirmed : QrPollOutcome

    /** 登录成功 */
    data class Succeeded(val account: Account) : QrPollOutcome

    /** 二维码已过期 */
    data object Expired : QrPollOutcome

    /** 网络或接口失败 */
    data class Failed(val message: String, val kind: FailureKind) : QrPollOutcome
}

/**
 * FR-1 登录：扫码登录 + Cookie 手动粘贴 + 登录态校验/持久化/退出。
 */
interface AuthRepository {
    val state: StateFlow<LoginState>

    /** 一次性提示（例如「登录已过期，请重新登录」），取走后清空 */
    val notice: StateFlow<String?>

    suspend fun refreshLoginState(): LoginState

    suspend fun createQrSession(): ApiResult<QrSession>

    suspend fun pollQrSession(qrcodeKey: String): QrPollOutcome

    suspend fun loginWithRawCookie(raw: String): ApiResult<Account>

    suspend fun logout()

    fun consumeNotice()

    fun isLoggedIn(): Boolean = state.value is LoginState.LoggedIn
}

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val api: BiliApi,
    private val cookieStore: CookieStore,
    private val accountStore: AccountStore,
    private val wbiSigner: WbiSigner,
) : AuthRepository {

    private val _state = MutableStateFlow<LoginState>(LoginState.Unknown)
    override val state: StateFlow<LoginState> = _state.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    override val notice: StateFlow<String?> = _notice.asStateFlow()

    override suspend fun refreshLoginState(): LoginState {
        if (!cookieStore.hasSessData) {
            accountStore.clear()
            _state.value = LoginState.LoggedOut
            return LoginState.LoggedOut
        }

        return when (val result = apiCall(maxAttempts = 2) { api.nav() }) {
            is ApiResult.Success -> {
                val data = result.data
                if (data.isLogin) {
                    val account = Account(
                        mid = data.mid,
                        name = data.uname.ifBlank { "B站用户" },
                        faceUrl = data.face,
                        isVip = data.vipStatus == 1,
                    )
                    accountStore.save(account)
                    _state.value = LoginState.LoggedIn(account)
                    LoginState.LoggedIn(account)
                } else {
                    // 有 Cookie 但服务端说不算登录 → 登录态已失效
                    expireSession()
                    LoginState.LoggedOut
                }
            }

            is ApiResult.Error -> {
                if (result.failure.kind == FailureKind.AUTH) {
                    expireSession()
                    LoginState.LoggedOut
                } else {
                    // 网络问题不要把用户踢下线：有缓存就先用缓存
                    val cached = accountStore.load()
                    if (cached != null) {
                        _state.value = LoginState.LoggedIn(cached)
                        LoginState.LoggedIn(cached)
                    } else {
                        _state.value = LoginState.LoggedOut
                        LoginState.LoggedOut
                    }
                }
            }
        }
    }

    override suspend fun createQrSession(): ApiResult<QrSession> =
        when (val result = apiCall(maxAttempts = 3) { api.qrGenerate() }) {
            is ApiResult.Success -> {
                val data = result.data
                if (data.url.isBlank() || data.qrcodeKey.isBlank()) {
                    ApiResult.Error(Failure("二维码生成失败，请重试", null, FailureKind.UNKNOWN))
                } else {
                    ApiResult.Success(QrSession(qrcodeKey = data.qrcodeKey, content = data.url))
                }
            }

            is ApiResult.Error -> result
        }

    override suspend fun pollQrSession(qrcodeKey: String): QrPollOutcome =
        when (val result = apiCall(maxAttempts = 2) { api.qrPoll(qrcodeKey) }) {
            is ApiResult.Success -> {
                val data = result.data
                when (data.code) {
                    0 -> {
                        // 兜底：登录成功后 url 的 query 里也带着 Cookie
                        cookieStore.mergeFromLoginUrl(data.url)
                        val account = fetchAndSaveAccount()
                        if (account != null) {
                            _state.value = LoginState.LoggedIn(account)
                            QrPollOutcome.Succeeded(account)
                        } else {
                            QrPollOutcome.Failed("登录成功但获取账号信息失败，请重试", FailureKind.UNKNOWN)
                        }
                    }

                    86101 -> QrPollOutcome.WaitingScan
                    86090 -> QrPollOutcome.ScannedNotConfirmed
                    86038 -> QrPollOutcome.Expired
                    else -> QrPollOutcome.Failed(
                        data.message.ifBlank { "扫码登录失败（${data.code}）" },
                        FailureKind.UNKNOWN,
                    )
                }
            }

            is ApiResult.Error -> QrPollOutcome.Failed(result.failure.userMessage, result.failure.kind)
        }

    override suspend fun loginWithRawCookie(raw: String): ApiResult<Account> {
        val parsed = cookieStore.parseRawCookieInput(raw)
        if (parsed.isEmpty()) {
            return ApiResult.Error(
                Failure(
                    "没有识别到有效 Cookie。请粘贴完整的 Cookie 字符串（形如 SESSDATA=xxx; bili_jct=yyy），或只粘贴 SESSDATA 的值。",
                    null,
                    FailureKind.UNKNOWN,
                ),
            )
        }
        if (!parsed.containsKey(CookieStore.KEY_SESSDATA)) {
            return ApiResult.Error(
                Failure("这段内容里没有 SESSDATA，无法用于登录。", null, FailureKind.UNKNOWN),
            )
        }

        cookieStore.replaceAll(parsed)
        return when (val result = apiCall(maxAttempts = 2) { api.nav() }) {
            is ApiResult.Success -> {
                val data = result.data
                if (data.isLogin) {
                    val account = Account(
                        mid = data.mid,
                        name = data.uname.ifBlank { "B站用户" },
                        faceUrl = data.face,
                        isVip = data.vipStatus == 1,
                    )
                    accountStore.save(account)
                    _state.value = LoginState.LoggedIn(account)
                    ApiResult.Success(account)
                } else {
                    clearSession()
                    ApiResult.Error(
                        Failure("Cookie 无效或已过期，请重新从浏览器获取。", null, FailureKind.AUTH),
                    )
                }
            }

            is ApiResult.Error -> {
                if (result.failure.kind == FailureKind.AUTH) clearSession()
                result
            }
        }
    }

    override suspend fun logout() {
        clearSession()
    }

    override fun consumeNotice() {
        _notice.value = null
    }

    private suspend fun fetchAndSaveAccount(): Account? =
        when (val result = apiCall(maxAttempts = 2) { api.nav() }) {
            is ApiResult.Success -> {
                val data = result.data
                if (!data.isLogin) {
                    null
                } else {
                    val account = Account(
                        mid = data.mid,
                        name = data.uname.ifBlank { "B站用户" },
                        faceUrl = data.face,
                        isVip = data.vipStatus == 1,
                    )
                    accountStore.save(account)
                    account
                }
            }

            is ApiResult.Error -> null
        }

    private fun expireSession() {
        clearSession()
        _notice.value = "登录已过期，请重新登录"
    }

    private fun clearSession() {
        cookieStore.clear()
        accountStore.clear()
        wbiSigner.invalidate()
        _state.value = LoginState.LoggedOut
    }
}
