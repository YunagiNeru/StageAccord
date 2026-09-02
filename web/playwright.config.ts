import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: "line",
  use: {
    baseURL: "http://127.0.0.1:4175",
    browserName: "chromium",
    launchOptions: { args: ["--disable-gpu"] },
    locale: "ja-JP",
    colorScheme: "light",
    reducedMotion: "reduce",
  },
  webServer: {
    command: "pnpm preview --host 127.0.0.1 --port 4175",
    url: "http://127.0.0.1:4175",
    reuseExistingServer: false,
  },
});
