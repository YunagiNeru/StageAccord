import { expect, test, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import path from "node:path";
import { ids, installApiFixture } from "./api-fixture";

const evidence = path.resolve(process.cwd(), "../.verification/phase-9-system");

async function settleVisualState(page: Page) {
  await page.evaluate(() => document.fonts.ready);
  await page.waitForTimeout(500);
  await page.screenshot({ animations: "disabled" });
  await page.waitForTimeout(1500);
}

test.beforeEach(async ({ page }) => {
  page.on("pageerror", (error) => { throw error; });
  await installApiFixture(page);
});

const desktopSurfaces = [
  ["requests", "/app/requests", "受付"],
  ["services", "/app/services", "サービス"],
  ["workflows", "/app/workflows", "ワークフロー"],
  ["project", `/app/projects/${ids.project}`, "プロジェクト"],
  ["settings-notifications", "/app/settings/notifications", "通知設定"],
  ["settings-billing", "/app/settings/billing", "請求"],
  ["settings-privacy", "/app/settings/privacy", "データとプライバシー"],
  ["admin-reports", "/admin/reports", "運用管理"],
  ["admin-support", "/admin/support", "運用管理"],
  ["admin-kill-switches", "/admin/kill-switches", "運用管理"],
  ["public-creator", "/creators/northline", "Northline Studio"],
  ["public-service", "/services/video-package", "映像パッケージ"],
  ["client-portal", `/portal/projects/${ids.access}`, "プロジェクト"],
  ["request-form", "/services/video-package/request", "制作のご依頼"],
] as const;

for (const [name, url, heading] of desktopSurfaces) {
  test(`${name} の実画面をデスクトップで表示する`, async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 1000 });
    await page.goto(url);
    await expect(page.getByRole("heading", { name: heading, exact: true }).first()).toBeVisible();
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth === document.documentElement.clientWidth)).toBe(true);
    await settleVisualState(page);
    await page.locator("html").screenshot({ path: path.join(evidence, `${name}-1440.png`), animations: "disabled" });
  });
}

const responsiveSurfaces = [
  [320, "/creators/northline", "Northline Studio", "public-creator"],
  [375, "/services/video-package", "映像パッケージ", "public-service"],
  [414, `/portal/projects/${ids.access}`, "プロジェクト", "client-portal"],
  [768, `/app/projects/${ids.project}`, "プロジェクト", "project"],
] as const;

for (const [width, url, heading, name] of responsiveSurfaces) {
  test(`${name} が ${width}px で横溢れしない`, async ({ page }) => {
    await page.setViewportSize({ width, height: width === 768 ? 1024 : 844 });
    await page.goto(url);
    await expect(page.getByRole("heading", { name: heading, exact: true }).first()).toBeVisible();
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth === document.documentElement.clientWidth)).toBe(true);
    await settleVisualState(page);
    await page.locator("html").screenshot({ path: path.join(evidence, `${name}-${width}.png`), animations: "disabled" });
  });
}

for (const state of ["loading", "empty", "forbidden", "expired", "conflict", "degraded"] as const) {
  test(`受付の ${state} 状態を区別して表示する`, async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto(`/app/requests?state=${state}`);
    if (state === "loading") await expect(page.getByLabel("読み込み中")).toBeVisible();
    else await expect(page.getByRole(state === "degraded" ? "alert" : "status")).toBeVisible();
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth === document.documentElement.clientWidth)).toBe(true);
    await page.getByRole("heading", { name: "受付", exact: true }).scrollIntoViewIfNeeded();
    await page.evaluate(() => {
      if (document.scrollingElement) document.scrollingElement.scrollTop = 0;
    });
    await expect.poll(() => page.evaluate(() => document.scrollingElement?.scrollTop ?? -1)).toBe(0);
    await settleVisualState(page);
    await page.locator("html").screenshot({ path: path.join(evidence, `requests-${state}-390.png`), animations: "disabled" });
  });
}

test("プロジェクトの進捗をAPIへ投稿する", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto(`/app/projects/${ids.project}`);
  await page.getByRole("tab", { name: "進捗" }).click();
  await page.getByLabel("進捗", { exact: true }).fill("確認用の進捗です");
  const sent = page.waitForRequest((request) => request.url().includes("/progress-updates") && request.method() === "POST");
  await page.getByRole("button", { name: "投稿" }).click();
  expect((await sent).postDataJSON()).toMatchObject({ visibility: "client", body: "確認用の進捗です" });
  await expect(page.getByText("進捗を記録しました")).toBeVisible();
});

test("通知・コマンドパレット・キーボードフォーカスを操作する", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto("/app");
  await page.getByRole("button", { name: "通知" }).click();
  await expect(page.getByLabel("通知一覧")).toBeVisible();
  await settleVisualState(page);
  await page.locator("html").screenshot({ path: path.join(evidence, "notifications-1440.png"), animations: "disabled" });
  await page.keyboard.press("Control+k");
  await expect(page.getByRole("dialog", { name: "移動・検索" })).toBeVisible();
  await expect(page.getByLabel("移動先を検索")).toBeFocused();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog", { name: "移動・検索" })).not.toBeVisible();
  await page.keyboard.press("Tab");
  const outlineWidth = await page.evaluate(() => getComputedStyle(document.activeElement as Element).outlineWidth);
  expect(outlineWidth).not.toBe("0px");
});

test("依頼フォームを送信して完了状態を表示する", async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 844 });
  await page.goto("/services/video-package/request");
  await page.getByLabel("メールアドレス").fill("requester@example.invalid");
  await page.getByLabel("依頼内容").fill("30秒のオープニング映像を希望します");
  await page.getByRole("checkbox", { name: "プライバシー条件に同意する" }).check();
  const sent = page.waitForRequest((request) => request.url().endsWith("/requests") && request.method() === "POST");
  await page.getByRole("button", { name: "依頼を送信" }).click();
  expect((await sent).postDataJSON()).toMatchObject({
    email: "requester@example.invalid",
    privacyAccepted: true,
    answers: { summary: "30秒のオープニング映像を希望します" },
  });
  await expect(page.getByRole("heading", { name: "依頼を受け付けました" })).toBeVisible();
  await settleVisualState(page);
  await page.locator("html").screenshot({ path: path.join(evidence, "request-complete-375.png"), animations: "disabled" });
});

for (const [name, url] of [
  ["認証済みワークベンチ", `/app/projects/${ids.project}`],
  ["公開サービス", "/services/video-package"],
  ["依頼者ポータル", `/portal/projects/${ids.access}`],
  ["拒否状態", "/app/requests?state=forbidden"],
] as const) {
  test(`${name} に自動検出可能なアクセシビリティ違反がない`, async ({ page }) => {
    await page.goto(url);
    await settleVisualState(page);
    const scan = await new AxeBuilder({ page }).analyze();
    expect(scan.violations).toEqual([]);
  });
}

test("削除確認モーダルに自動検出可能なアクセシビリティ違反がない", async ({ page }) => {
  await page.goto("/app/settings/privacy");
  await page.getByRole("button", { name: "削除要求を開始" }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await settleVisualState(page);
  await page.locator("html").screenshot({ path: path.join(evidence, "deletion-modal-1440.png"), animations: "disabled" });
  const scan = await new AxeBuilder({ page }).analyze();
  expect(scan.violations).toEqual([]);
});
