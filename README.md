# imToken 静态首页（学习用途镜像）

这是从原网站抓取并清理后的纯静态 HTML/CSS/JS 站点，已移除第三方跟踪脚本
（Google/百度统计、HelpScout、Cloudflare Rocket Loader/Insights 等）和被
混淆的邮箱，可直接部署在 Cloudflare Pages 上，仅用于学校学习项目，非实际
上线产品。

## 部署到 Cloudflare Pages

### 方式一：直接拖拽上传（最简单）
1. 打开 https://dash.cloudflare.com/ → 左侧 "Workers & Pages" → "创建应用程序" → "Pages" → "上传资产"
2. 把这个文件夹里的所有文件（index.html、css/、js/、images/、fonts/ 等）直接拖进去上传
   （注意：是上传文件夹里的内容，不要多包一层目录）
3. 起个项目名，点击部署，几秒后即可通过 `*.pages.dev` 域名访问

### 方式二：用 Wrangler 命令行部署
```bash
npm install -g wrangler
wrangler login
wrangler pages deploy . --project-name=imtoken-demo
```

### 方式三：连接 Git 仓库自动部署
把这些文件提交到 GitHub/GitLab 仓库后，在 Cloudflare Pages 里选择
"连接到 Git"，构建命令留空，构建输出目录填 `/`（根目录）即可，因为
这已经是构建好的静态文件，不需要任何打包步骤。

## 注意事项
- 这是纯静态文件，没有后端，页面上原本依赖远程接口的"公告栏""帮助中心文章"
  "合作状态"等动态内容请求会失败（控制台报错），但不影响页面正常显示，
  如要完全消除报错可在 index.html 里找到对应的 fetch 请求并删除/注释掉。
- 页面内所有图片、字体、脚本都已改为相对路径，部署到任意子路径或自定义
  域名下都能正常加载。
- 原站的联系邮箱 `support@token.im` / `bd@token.im` 之前是被 Cloudflare
  混淆隐藏的，已在本地还原为明文 mailto 链接。
