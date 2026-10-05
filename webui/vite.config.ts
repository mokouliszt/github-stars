import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import path from "node:path";

// 成果物を Android の assets/webui へ直接出力する。
export default defineConfig({
  plugins: [react()],
  base: "./",
  resolve: { alias: { "@": path.resolve(__dirname, "src") } },
  build: {
    outDir: path.resolve(__dirname, "../app/src/main/assets/webui"),
    emptyOutDir: true,
    target: "es2017",
    assetsInlineLimit: 0,
  },
});
