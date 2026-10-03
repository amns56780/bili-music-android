package com.bilimusic.app

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 6 真机 UI 测试：队列 BottomSheet（FR-4）与定时关闭 BottomSheet（FR-5）。
 *
 * 走法：歌单列表 → 第一个歌单 → 播放全部 → 播放页
 *   → 点队列按钮，断言「播放队列」面板出现且列出曲目
 *   → 点定时关闭，断言面板出现，输入 1 分钟并开始，断言按钮上出现「⏱ 剩余 …」
 *
 * 用 Compose 测试框架点击（按文本/语义），不依赖屏幕坐标，也不经过系统输入法。
 *
 * 运行：
 * ```
 * adb shell am instrument -w -e class com.bilimusic.app.SleepTimerUiTest \
 *   com.bilimusic.app.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
class SleepTimerUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun 队列页与定时关闭面板都可用() {
        // 1. 等歌单列表 → 进入第一个歌单
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithText("我的歌单").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodes(
            hasClickAction() and hasText("首 ·", substring = true),
        ).onFirst().performClick()

        // 2. 播放全部
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithText("播放全部").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("播放全部").performClick()

        // 3. 播放页出现「定时关闭」按钮
        composeRule.waitUntil(timeoutMillis = 25_000) {
            composeRule.onAllNodesWithText("定时关闭").fetchSemanticsNodes().isNotEmpty()
        }

        // 4. FR-4：打开队列面板，断言标题与「共 N 首」
        composeRule.onNodeWithContentDescription("播放队列").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("播放队列").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("共 5 首").assertExists()
        composeRule.onNodeWithText("序号就是当前真实播放顺序；左滑移除，长按可上移/下移").assertExists()
        // 关闭队列面板
        composeRule.onNodeWithContentDescription("收起播放页").assertExists()

        // 5. FR-5：打开定时关闭面板
        composeRule.onNodeWithText("定时关闭").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("播完当前歌曲后停止").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("15 分钟").assertExists()
        composeRule.onNodeWithText("120 分钟").assertExists()

        // 6. 自定义 1 分钟并开始
        composeRule.onNode(hasSetTextAction()).performTextReplacement("1")
        composeRule.onNodeWithText("开始").performClick()

        // 7. 定时生效：播放页按钮变成倒计时标签
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("⏱ 剩余", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
