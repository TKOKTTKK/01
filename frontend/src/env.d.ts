/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

// Protobuf + Gzip 重构新增：用 Vite 的 `?raw` 后缀把 .proto 文件当纯文本导入，
// 喂给 protobufjs 在运行时动态 parse（见 src/api/protoQuoteIntraday.ts）。
declare module '*.proto?raw' {
  const source: string
  export default source
}

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<Record<string, unknown>, Record<string, unknown>, unknown>
  export default component
}
