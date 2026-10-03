package com.bilimusic.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * FR-1「手动粘贴 Cookie」的真实 UI 路径测试：
 * 展开折叠区 → 往输入框里输入整段 Cookie → 点「用 Cookie 登录」→ 断言跳到歌单页。
 *
 * 注意：这里用 Compose 测试框架输入文本（直接走语义动作 / InputConnection），
 * **不经过系统输入法**，所以不受中文输入法把 `_` 转成中文标点的影响。
 *
 * 前提：运行前 App 处于未登录状态（停在登录页）。
 * 运行：
 * ```
 * adb shell am instrument -w -e cookie 'SESSDATA=...' \
 *   -e class com.bilimusic.app.LoginScreenUiTest \
 *   com.bilimusic.app.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
class LoginScreenUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun 输入整段Cookie并点按钮登录成功() {
        val cookie = InstrumentationRegistry.getArguments().getString("cookie").orEmpty()
        assumeTrue("未传 -e cookie，跳过", cookie.isNotBlank())

        // 1. 展开「手动粘贴 Cookie」折叠区
        composeRule.onNodeWithText("扫码不方便？手动粘贴 Cookie").performClick()
        composeRule.waitForIdle()

        // 2. 滚动到输入框 → 聚焦 → 整体替换文本
        val field = composeRule.onNode(hasSetTextAction())
        field.performScrollTo()
        field.performClick()
        composeRule.waitForIdle()
        field.performTextReplacement(cookie)
        composeRule.waitForIdle()

        // 3. 断言文字真的进去了（长度一致），否则后面的点击只会是无效点击
        val editable = composeRule.onNode(hasSetTextAction())
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.EditableText)
            ?.text
            .orEmpty()
        assertEquals("输入框内容长度应与 Cookie 长度一致", cookie.length, editable.length)

        // 4. 点「用 Cookie 登录」
        composeRule.onNodeWithText("用 Cookie 登录").performScrollTo().performClick()

        // 5. 登录成功后根导航会切到歌单页（标题「我的歌单」）
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText("我的歌单").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
