import { expect, test } from "@playwright/test";
import path from "node:path";

const evidenceDirectory = path.resolve(process.cwd(), "../.verification/phase-1-web");
const widths = [320, 375, 414, 768] as const;

for (const width of widths) {
  test(`概要が横溢れせず ${width}px で表示される`, async ({ page }) => {
    await page.setViewportSize({ width, height: width === 768 ? 1024 : 844 });
    await page.goto("/app");
    await expect(page.getByRole("heading", { name: "概要" })).toBeVisible();
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth)).toBe(width);
    await page.screenshot({ path: path.join(evidenceDirectory, `dashboard-${width}-verified.png`), fullPage: true });
  });
}

test("デスクトップの概要とコマンドパレットを表示する", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto("/app");
  await expect(page.getByText("Northline Studio")).toBeVisible();
  await page.screenshot({ path: path.join(evidenceDirectory, "dashboard-1440-verified.png"), fullPage: true });
  await page.getByRole("button", { name: "移動・検索" }).click();
  await expect(page.getByRole("dialog", { name: "移動・検索" })).toBeVisible();
  await page.screenshot({ path: path.join(evidenceDirectory, "command-palette-1440.png"), fullPage: true });
});

test("ログイン画面の入力エラーを表示する", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/login");
  await page.getByLabel("メールアドレス").fill("invalid");
  await page.getByRole("button", { name: "メールで続ける" }).click();
  await expect(page.getByText("メールアドレスの形式を確認してください。")).toBeVisible();
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth)).toBe(390);
  await page.screenshot({ path: path.join(evidenceDirectory, "login-error-390.png"), fullPage: true });
});

test("部分障害で再読込操作を残す", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/app?session=degraded");
  await expect(page.getByRole("button", { name: "再読み込み" })).toBeVisible();
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth)).toBe(390);
  await page.screenshot({ path: path.join(evidenceDirectory, "degraded-390-verified.png"), fullPage: true });
});
