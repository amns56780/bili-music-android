package com.bilimusic.app.data.local.prefs

import com.bilimusic.app.domain.model.Account
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 账号信息本地快照。
 * 作用：启动校验登录态时如果**网络不通**，用缓存信息继续显示已登录，
 * 而不是把用户在离线状态下踢回登录页。
 */
@Singleton
class AccountStore @Inject constructor(
    private val securePrefs: SecurePrefs,
    private val json: Json,
) {

    fun save(account: Account) {
        val blob = json.encodeToString(
            Snapshot(account.mid, account.name, account.faceUrl, account.isVip),
        )
        securePrefs.preferences.edit().putString(KEY_ACCOUNT, blob).apply()
    }

    fun load(): Account? {
        val raw = securePrefs.preferences.getString(KEY_ACCOUNT, null) ?: return null
        return runCatching {
            val snapshot = json.decodeFromString<Snapshot>(raw)
            Account(
                mid = snapshot.mid,
                name = snapshot.name,
                faceUrl = snapshot.faceUrl,
                isVip = snapshot.isVip,
            )
        }.getOrNull()
    }

    fun clear() {
        securePrefs.preferences.edit().remove(KEY_ACCOUNT).apply()
    }

    @kotlinx.serialization.Serializable
    private data class Snapshot(
        val mid: Long,
        val name: String,
        val faceUrl: String,
        val isVip: Boolean,
    )

    private companion object {
        const val KEY_ACCOUNT = "account_snapshot"
    }
}
