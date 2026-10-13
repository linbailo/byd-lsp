# 已回退功能的实现存档

这里的文件**不参与构建**（不在 `byd-lsp/app/src` 之内，Gradle 不会编译它）。

保留原因只有一个：**1.21.0 曾经加过一个「本机 HTTP 取码接口」**
（对应原 [wxcode](https://github.com/xiaojinwen/wxcode) 的 `LoginHttpServer`：
在微信进程里起一个只监听 `127.0.0.1` 的 HTTP 服务，用
`/login?appId=<小程序AppID>` 取 code），后来按使用者的要求回退到 1.20.3，
这份实现就挪到这里留档 —— 需要时可以接回去，不必重写。

| 文件 | 说明 |
|---|---|
| `CodeHttpServer-1.21.0.java` | 当时的完整实现（只用 JDK 的 `ServerSocket`，不依赖 NanoHTTPD；含 `/`、`/login`、`/ping`、`/whoami` 四个入口与回环绑定） |

如果决定不要它了，删掉整个目录即可，仓库其它部分不受影响。
