# 官方模块库用的文案

这个目录里是**给 `Xposed-Modules-Repo/io.github.linbailo.byd` 那个仓库用的现成文案**，
不是本仓库的文档正文。

| 文件 | 放到哪里 |
|---|---|
| `SUMMARY`（没有扩展名） | 直接作为那个仓库根目录的 `SUMMARY` 文件，LSPosed 首页显示的就是它 |
| `RELEASE-NOTES.md` | 发 Release 时粘进「Describe this release」；标题写 `versionName`，标签写 `versionCode-versionName` |

## 发新版时在那个仓库里做什么

那个仓库是机器人建的，**网页操作**（官方说明：仓库描述用 API 改不了，权限不够）：

1. 先看 `SUMMARY` 有没有要改的，改了就顺手同步过去；
2. 新建 Release：标题 `versionName`（如 `1.22.0`）、**标签必须是 `versionCode-versionName`**（如 `28-1.22.0`）、
   说明粘下面的正文、**附上本仓库 Release 里那个已签名的 APK**；
3. 只换 APK 而不重新发 Release 的话，机器人收不到通知、标签不会更新 ——
   所以每次发版都按第 2 步完整走一遍；
4. 完后 5~60 分钟，列表重建，LSPosed Manager 里会提示更新。

## 1.22.0 的 Release 说明（可粘）

```
包名从 xiaojw.byd 换成 io.github.linbailo.byd，以满足官方模块库的命名要求。

- applicationId → io.github.linbailo.byd；namespace 与 Java 包名仍是 xiaojw.byd.*，广播 action 常量不变。
- 升级注意：包名变了就是另一个应用，安装前必须先卸载旧版，并在 LSPosed 里重新勾选新包名的作用域。
- 控制台配置需重配一次（旧的 config.json 可复制到新目录）；签到状态存在微信自己的 SharedPreferences 里，不会丢。
- 产物核验：单 dex（166180 B）、xposed_init 23 B、包内 WebUI 与源逐字节一致、无 debuggable、签名 v2 有效。
- 完整改动见 https://github.com/linbailo/byd-lsp/blob/main/CHANGELOG.md
```

## 以后的模板

```
<这一版最主要的一件事，一句话>

- 改了什么、为什么（对使用者有影响的部分）
- 升级注意：要不要先卸载 / 要不要重勾作用域 / 配置要不要重配
- 产物核验：单 dex（<字节数>）、xposed_init 23 B、包内 WebUI 与源逐字节一致、无 debuggable、签名 v2 有效
- 完整改动见 https://github.com/linbailo/byd-lsp/blob/main/CHANGELOG.md
```

> 说明：正文里的数字（dex 字节数、体积等）取自本仓库上一节「构建产物已核验项」。
> 每一版这些数字都会变，粘之前对着核验表更新一遍。
