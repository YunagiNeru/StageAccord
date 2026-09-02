import { expect, test } from "@playwright/test";
import path from "node:path";
import { installApiFixture } from "./api-fixture";

test.beforeEach(async ({ page }) => { await installApiFixture(page); });

const evidenceDirectory = path.resolve(process.cwd(), "../.verification/phase-1-web");
const widths = [320, 375, 414, 768] as const;

async function settleVisualState(page: import("@playwright/test").Page) {
  await page.evaluate(() => document.fonts.ready);
  await page.waitForTimeout(500);
  await page.screenshot({ animations: "disabled" });
  await page.waitForTimeout(500);
}

for (const width of widths) {
  test(`概要が横溢れせず ${width}px で表示される`, async ({ page }) => {
    await page.setViewportSize({ width, height: width === 768 ? 1024 : 844 });
    await page.goto("/app");
    await expect(page.getByRole("heading", { name: "概要" })).toBeVisible();
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth === document.documentElement.clientWidth)).toBe(true);
    await settleVisualState(page);
    await page.screenshot({ path: path.join(evidenceDirectory, `dashboard-${width}-verified.png`), fullPage: true, animations: "disabled" });
  });
}

test("デスクトップの概要とコマンドパレットを表示する", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto("/app");
  await expect(page.getByText("ワークスペース")).toBeVisible();
  await settleVisualState(page);
  await page.screenshot({ path: path.join(evidenceDirectory, "dashboard-1440-verified.png"), animations: "disabled" });
  await page.getByRole("button", { name: "移動・検索" }).click();
  await expect(page.getByRole("dialog", { name: "移動・検索" })).toBeVisible();
  await settleVisualState(page);
  await page.screenshot({ path: path.join(evidenceDirectory, "command-palette-1440.png"), animations: "disabled" });
});

test("ログイン画面の入力エラーを表示する", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/login");
  await page.getByLabel("メールアドレス").fill("invalid");
  await page.getByRole("button", { name: "ログイン" }).click();
  await expect(page.getByText("メールアドレスの形式を確認してください。")).toBeVisible();
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth === document.documentElement.clientWidth)).toBe(true);
  await settleVisualState(page);
  await page.screenshot({ path: path.join(evidenceDirectory, "login-error-390.png"), fullPage: true, animations: "disabled" });
});

test("部分障害で再読込操作を残す", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/app?session=degraded");
  await expect(page.getByRole("button", { name: "再読み込み" })).toBeVisible();
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth === document.documentElement.clientWidth)).toBe(true);
  await settleVisualState(page);
  await page.screenshot({ path: path.join(evidenceDirectory, "degraded-390-verified.png"), fullPage: true, animations: "disabled" });
});
