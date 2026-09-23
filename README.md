# Claude Android WebApp (Safe Sandbox Edition)

一个专为防风控与隐私安全设计的 Claude.ai 原生包装 Android 应用。

## 核心特性

- 🛡️ **严格沙盒保护**：绝不索取任何系统敏感权限（无 SIM 卡读取、无 IMEI/Android ID 滥用、无定位、无基站信息）。
- 🔑 **支持 Google OAuth 一键登录**：去除 WebView 默认标记，规避 Google `403: disallowed_useragent` 限制。
- 📎 **完整文件/图片上传**：支持直接在聊天界面选择并上传图片、文档、代码文件。
- 🔄 **智能下拉刷新**：顶部下拉快速刷新，页面滚动时不误触。
- 🔙 **原生返回手势支持**：支持返回上一页，防止误触直接退回到桌面。
- 🎨 **沉浸式暗色设计**：与 Claude 官方暗黑模式无缝契合，启动无白屏闪烁。

## 自动化构建

本项目已配置 GitHub Actions 自动化构建，每次 push 均会自动编译并生成 Release APK。
