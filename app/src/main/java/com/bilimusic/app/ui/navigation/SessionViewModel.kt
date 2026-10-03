package com.bilimusic.app.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bilimusic.app.data.repository.AuthRepository
import com.bilimusic.app.data.repository.LoginState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 根级登录态：启动时校验一次，之后驱动导航（未登录 → 登录页；退出登录 → 回登录页）。
 */
@HiltViewModel
class SessionViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    val loginState: StateFlow<LoginState> = authRepository.state

    val notice: StateFlow<String?> = authRepository.notice

    /** 每次冷启动只校验一次（页面切来切去不重复打接口） */
    private var validated = false

    fun validateOnce() {
        if (validated) return
        validated = true
        viewModelScope.launch { authRepository.refreshLoginState() }
    }

    fun consumeNotice() = authRepository.consumeNotice()
}
