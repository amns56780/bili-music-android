## 这个 PR 做了什么

<!-- 一句话说清改了什么、为什么 -->

关联 Issue：<!-- 例如 Closes #12 -->

## 类型

- [ ] 🐞 Bug 修复
- [ ] ✨ 新功能
- [ ] 🎨 界面 / 适配
- [ ] ♻️ 重构（不改变行为）
- [ ] 📝 文档
- [ ] 🔧 构建 / 依赖

## 自检（请逐条确认）

- [ ] `./gradlew assembleDebug` 零错误
- [ ] `./gradlew testDebugUnitTest` 全绿
- [ ] 没有引入 `TODO()` / `FIXME` / 空实现 / `onClick = {}`（**每个按钮都必须真的能用**）
- [ ] 没有提交 `local.properties` / keystore / **真实 SESSDATA 或 Cookie** / 抓包取流 URL
- [ ] 新增或改动的用户可见文案是简体中文
- [ ] 有对应改动的话，已更新 README 的 FR 表

## 验证方式

<!-- 你怎么确认它是好的？贴命令与关键输出，或真机截图 -->

```
# 例如
./gradlew assembleDebug testDebugUnitTest
adb shell dumpsys media_session | grep -A3 bilimusic
```

## 界面改动截图（可选）

| 改动前 | 改动后 |
| --- | --- |
|  |  |

## 补充说明

<!-- 有没有取舍、已知问题、后续计划 -->
