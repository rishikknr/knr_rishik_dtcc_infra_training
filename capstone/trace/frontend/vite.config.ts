import react from "@vitejs/plugin-react";
import { defineConfig, type ProxyOptions } from "vite";
const target = process.env.TRACE_BACKEND_URL ?? "http://127.0.0.1:8080";
const proxy: ProxyOptions = {
  target,
  changeOrigin: true,
  // Translate only a browser's actual same-origin Vite request to the backend origin.
  // Preserve forwarded and cross-site headers so the backend can reject them.
  configure: (server) => {
    server.on("proxyReq", (outgoing, incoming) => {
      if (incoming.headers.origin === `http://${incoming.headers.host}`)
        outgoing.setHeader("origin", target);
    });
  },
};
export default defineConfig({
  plugins: [react()],
  server: { host: "127.0.0.1", strictPort: true, proxy: { "/api": proxy } },
  preview: { host: "127.0.0.1", strictPort: true, proxy: { "/api": proxy } },
});
