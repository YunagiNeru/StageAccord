import { expect, test, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import path from "node:path";

const evidence = path.resolve(process.cwd(), "../.verification/phase-9-system");

async function settleVisualState(page: Page) {
  await page.waitForTimeout(500);
}

test.beforeEach(async ({ page }) => {
  page.on("pageerror", (error) => { throw error; });
});

const desktopSurfaces = [
  ["requests", "/app/requests", "受付"],
  ["services", "/app/services", "サービス"],
  ["workflows", "/app/workflows", "ワークフロー"],
  ["project", "/app/projects/demo-project", "ブランドサイト制作"],
  ["settings-notifications", "/app/settings/notifications", "通知設定"],
  ["settings-billing", "/app/settings/billing", "請求"],
  ["settings-privacy", "/app/settings/privacy", "データとプライバシー"],
  ["admin-reports", "/admin/reports", "運用管理"],
  ["admin-support", "/admin/support", "運用管理"],
  ["admin-kill-switches", "/admin/kill-switches", "運用管理"],
  ["public-creator", "/creators/northline", "Northline Studio"],
  ["public-service", "/services/video-package", "映像パッケージ"],
  ["client-portal", "/portal/projects/access-demo", "ブランドサイト制作"],
  ["request-form", "/services/video-package/request", "制作のご依頼"],
] as const;

for (const [name, url, heading] of desktopSurfaces) {
  test(`${name} の実画面をデスクトップで表示する`, async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 1000 });
    await page.goto(url);
    await expect(page.getByRole("heading", { name: heading, exact: true }).first()).toBeVisible();
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth)).toBe(1440);
    await settleVisualState(page);
    await page.screenshot({ path: path.join(evidence, `${name}-1440.png`), fullPage: true });
  });
}

const responsiveSurfaces = [
  [320, "/creators/northline", "Northline Studio", "public-creator"],
  [375, "/services/video-package", "映像パッケージ", "public-service"],
  [414, "/portal/projects/access-demo", "ブランドサイト制作", "client-portal"],
  [768, "/app/projects/demo-project", "ブランドサイト制作", "project"],
] as const;

for (const [width, url, heading, name] of responsiveSurfaces) {
  test(`${name} が ${width}px で横溢れしない`, async ({ page }) => {
    await page.setViewportSize({ width, height: width === 768 ? 1024 : 844 });
    await page.goto(url);
    await expect(page.getByRole("heading", { name: heading, exact: true }).first()).toBeVisible();
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth)).toBe(width);
    await settleVisualState(page);
    await page.screenshot({ path: path.join(evidence, `${name}-${width}.png`), fullPage: true });
  });
}

for (const state of ["loading", "empty", "forbidden", "expired", "conflict", "degraded"] as const) {
  test(`受付の ${state} 状態を区別して表示する`, async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto(`/app/requests?state=${state}`);
    if (state === "loading") await expect(page.getByLabel("読み込み中")).toBeVisible();
    else await expect(page.getByRole(state === "degraded" ? "alert" : "status")).toBeVisible();
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth)).toBe(390);
    await page.getByRole("heading", { name: "受付", exact: true }).scrollIntoViewIfNeeded();
    await page.evaluate(() => {
      if (document.scrollingElement) document.scrollingElement.scrollTop = 0;
    });
    await expect.poll(() => page.evaluate(() => document.scrollingElement?.scrollTop ?? -1)).toBe(0);
    await settleVisualState(page);
    await page.screenshot({ path: path.join(evidence, `requests-${state}-390.png`) });
  });
}

test("プロジェクトの投稿・承認モーダル・受領を操作する", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto("/app/projects/demo-project");
  await page.getByRole("tab", { name: "タイムライン" }).click();
  await page.getByLabel("進捗を共有").fill("確認用の進捗です");
  await page.getByRole("button", { name: "投稿" }).click();
  await expect(page.getByText("進捗を依頼者へ共有しました")).toBeVisible();
  await page.getByRole("tab", { name: "承認" }).click();
  await page.getByRole("button", { name: "対象版を確認して承認" }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await settleVisualState(page);
  await page.screenshot({ path: path.join(evidence, "approval-modal-1440.png"), fullPage: true });
  await page.getByRole("button", { name: "承認する" }).click();
  await expect(page.getByText("確認対象 v4 を承認しました")).toBeVisible();
  await page.getByRole("tab", { name: "納品" }).click();
  await page.getByRole("button", { name: "受領する" }).click();
  await expect(page.getByText("納品パッケージを受領済みにしました")).toBeVisible();
});

test("通知・コマンドパレット・キーボードフォーカスを操作する", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto("/app");
  await page.getByRole("button", { name: "通知" }).click();
  await expect(page.getByLabel("通知一覧")).toBeVisible();
  await settleVisualState(page);
  await page.screenshot({ path: path.join(evidence, "notifications-1440.png"), fullPage: true });
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
  await page.getByLabel("依頼内容").fill("30秒のオープニング映像を希望します");
  await page.getByRole("button", { name: "依頼を送信" }).click();
  await expect(page.getByRole("heading", { name: "依頼を受け付けました" })).toBeVisible();
  await settleVisualState(page);
  await page.screenshot({ path: path.join(evidence, "request-complete-375.png"), fullPage: true });
});

for (const [name, url] of [
  ["認証済みワークベンチ", "/app/projects/demo-project"],
  ["公開サービス", "/services/video-package"],
  ["依頼者ポータル", "/portal/projects/access-demo"],
  ["拒否状態", "/app/requests?state=forbidden"],
] as const) {
  test(`${name} に自動検出可能なアクセシビリティ違反がない`, async ({ page }) => {
    await page.goto(url);
    await settleVisualState(page);
    const scan = await new AxeBuilder({ page }).analyze();
    expect(scan.violations).toEqual([]);
  });
}

test("承認モーダルに自動検出可能なアクセシビリティ違反がない", async ({ page }) => {
  await page.goto("/app/projects/demo-project");
  await page.getByRole("tab", { name: "承認" }).click();
  await page.getByRole("button", { name: "対象版を確認して承認" }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
  const scan = await new AxeBuilder({ page }).analyze();
  expect(scan.violations).toEqual([]);
});
