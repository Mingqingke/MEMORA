# MEMORA

QQ 空间个人档案工具 · Android

把还能看到的说说 评论 图片 视频 好友关系存到本机
按年份翻 全文搜 离线看 随时导出

## 现在能做什么

- 官方页面登录或扫码登录 不接触密码
- 归档当前说说 历史互动 相册 好友
- 记录每条内容的来源 完整度 缺失字段
- 照片与视频本地浏览 系统播放器播视频
- 全库搜索 正文 评论 昵称
- 档案检查 文件校验 缺失重下
- 导出 ZIP 含离线 HTML 可导入回来
- 应用内更新 校验 SHA-256 后安装

## 不做的事

- 不收集密码 不绕过验证码 不绕过访问限制
- 不伪造恢复率 没有证据就标出来
- 不上传任何数据 只写本机

## 构建

    export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64
    /opt/gradle-9.4.1/bin/gradle assembleRelease --no-daemon

要求 JDK 21 · compileSdk 37 · minSdk 26

## 结构

    core/net        会话 请求 二维码登录 头像
    core/source     来源适配器 每个流独立游标
    core/recovery   证据 去重 状态折算
    core/task       任务 检查点 前台服务 媒体下载
    core/export     导出 导入 离线 HTML
    core/db         手写 SQLite DAO
    ui              Miuix 组件 四栏导航 设置栈

## 说明

接口路径来自公开文档与实测 未用真实账号全量验证
结构变化会显示「格式无法识别」而不是假数据
