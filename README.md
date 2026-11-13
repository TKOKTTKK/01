# imToken 移动端静态首页（学习用途镜像）

这是从移动端抓包工具导出的页面，经过整理清洗后的纯静态 HTML/CSS/JS 站点：
- 原抓包文件用完整请求 URL 命名（`ua=...&url=...token.im.html`），已改名为标准的 `index.html` 并修正了相对路径
- 移除了第三方跟踪脚本（Google/百度统计、HelpScout 客服组件）和 Cloudflare
  专属脚本（Rocket Loader、Web Analytics beacon、邮箱反爬混淆解码器）
- 两个被混淆隐藏的联系邮箱已还原为明文 mailto 链接
- 所有静态资源都在 `token.im/` 目录下，已校验页面引用的文件全部存在

仅用于学校学习项目，非实际上线产品。

## 部署到 Cloudflare

项目根目录已包含 `wrangler.toml`，配置为把当前目录作为静态资源发布：

```toml
name = "stock"
compatibility_date = "2026-11-13"

[assets]
directory = "."
```

在 Cloudflare 后台「Workers」项目的构建设置中填：
- 根目录：`/`
- 构建命令：留空（纯静态文件，不需要 npm 构建）
- 部署命令：`npx wrangler deploy`

也可以不用这套流程，改用 Cloudflare **Pages**（拖拽上传或 Git 集成，
框架预设选 None，构建命令留空，输出目录填 `/`），效果一样。

## 注意事项
- 页面上原本请求远程接口的"公告栏""帮助中心文章""合作状态"等动态内容，
  因为没有对应后端，加载会失败（控制台报错），但不影响页面正常显示。
- CSS 里有一处指向原文件名的渐变色引用（`fill: url(...#gradient_top_color)`），
  是原站自身的历史遗留问题，纯装饰性，不影响使用。
