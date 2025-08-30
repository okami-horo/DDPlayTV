# Bugly 配置指南

本项目已集成腾讯Bugly错误上报功能。要使用您自己的Bugly账号，请按照以下步骤配置：

## 1. 获取Bugly App ID

1. 访问腾讯Bugly官网：https://bugly.qq.com/
2. 注册/登录账号
3. 创建新应用或选择现有应用
4. 在应用设置页面中找到 **App ID**（类似：`9a2b3c4d5e`）

## 2. 配置项目

### 方案A：修改配置文件（推荐）

1. 打开文件：`common_component/src/main/java/com/xyoye/common_component/utils/SecurityHelperConfig.kt`

2. 将以下代码中的 `YOUR_BUGLY_APP_ID_HERE` 替换为您的实际Bugly App ID：

```kotlin
const val BUGLY_APP_ID = "您的Bugly_App_ID"
```

3. 保存文件并重新编译项目

### 方案B：使用local.properties文件

1. 复制 `local.properties.template` 为 `local.properties`
2. 编辑 `local.properties` 文件，填入您的配置
3. 该文件不会被提交到git，保护您的密钥安全

## 3. 验证配置

编译并运行应用后，错误将自动上报到您的Bugly控制台。您可以：

1. 在Bugly控制台查看错误报告
2. 使用 `ErrorReportHelper.postException()` 手动测试上报功能

## 4. 注意事项

- **不要将真实的App ID提交到公开的代码仓库**
- 建议将 `local.properties` 添加到 `.gitignore`
- 测试环境和正式环境可以使用不同的Bugly App ID

## 5. 错误上报使用方法

项目已提供 `ErrorReportHelper` 工具类，使用方法：

```kotlin
// 上报捕获的异常
try {
    // 可能出错的代码
} catch (e: Exception) {
    ErrorReportHelper.postCatchedException(e, "TagName", "额外信息")
}

// 上报自定义错误
ErrorReportHelper.postException("自定义错误信息", "TagName")
```

所有错误将自动上报到您配置的Bugly控制台。