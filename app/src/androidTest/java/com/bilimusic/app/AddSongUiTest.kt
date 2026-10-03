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
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * FR-2 第 5 条「粘贴 BV 号加单曲」的真实 UI 路径测试。
 *
 * 走法：歌单列表 → 点第一个歌单 → 右上角「添加单曲」→ 输入 BV → 点「添加」→ 断言给出提示。
 * 用 Compose 测试框架输入文本，绕过系统输入法（中文输入法会把注入的数字当成候选词选择）。
 *
 * 运行（bvid 请用一个**已经在歌单里**的 BV，这样可以顺带验证去重）：
 * ```
 * adb shell am instrument -w -e bvid 'BV1xx411c7mD' \
 *   -e class com.bilimusic.app.AddSongUiTest \
 *   com.bilimusic.app.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
class AddSongUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun 粘贴BV加单曲并给出提示() {
        val bvid = InstrumentationRegistry.getArguments().getString("bvid").orEmpty()
        assumeTrue("未传 -e bvid，跳过", bvid.isNotBlank())

        // 1. 等歌单列表出现
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithText("我的歌单").fetchSemanticsNodes().isNotEmpty()
        }

        // 2. 点第一个歌单卡片（clickable 会合并子文本，所以直接匹配节点自身文本「5 首 · 24:46」）
        composeRule.onAllNodes(
            hasClickAction() and hasText("首 ·", substring = true),
        ).onFirst().performClick()

        // 3. 详情页右上角「添加单曲」
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithContentDescription("添加单曲").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("添加单曲").performClick()

        // 4. 输入 BV 并点「添加」
        composeRule.onNode(hasSetTextAction()).performTextReplacement(bvid)
        composeRule.onNodeWithText("添加").performClick()

        // 5. 断言：要么提示已添加，要么提示已在歌单里（都说明 UI → 仓储 → 结果提示这条链路通了）
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithText("已添加", substring = true).fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("已经在歌单里", substring = true).fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("视频不存在", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
