import type { Page, Route } from "@playwright/test";

export const ids = {
  workspace: "00000000-0000-4000-8000-000000000001", account: "00000000-0000-4000-8000-000000000002",
  project: "00000000-0000-4000-8000-000000000003", checkpoint: "00000000-0000-4000-8000-000000000004",
  request: "00000000-0000-4000-8000-000000000005", service: "00000000-0000-4000-8000-000000000006",
  workflow: "00000000-0000-4000-8000-000000000007", notification: "00000000-0000-4000-8000-000000000008",
  report: "00000000-0000-4000-8000-000000000009", access: "00000000-0000-4000-8000-000000000010",
} as const;

const json = (route: Route, body: unknown, status = 200) => route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });

export async function installApiFixture(page: Page) {
  await page.route("**/api/v1/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname.replace("/api/v1", "");
    const method = request.method();
    const scenario = new URL(page.url()).searchParams.get("state");
    if (path === "/auth/session") {
      if (new URL(page.url()).searchParams.get("session") === "degraded") return json(route, { code: "SERVICE_UNAVAILABLE" }, 503);
      return json(route, { sessionId: ids.account, accountId: ids.account, authStrength: "mfa", authenticatedAt: "2026-09-02T09:00:00Z", lastSeenAt: "2026-09-02T09:00:00Z", absoluteExpiresAt: "2026-09-09T09:00:00Z", revoked: false });
    }
    if (path.endsWith("/requests") && method === "GET") {
      if (scenario === "loading") await new Promise((resolve) => setTimeout(resolve, 2500));
      if (scenario === "empty") return json(route, []);
      if (scenario === "forbidden") return json(route, { code: "AUTHORIZATION_DENIED", detail: "権限がありません。" }, 403);
      if (scenario === "expired") return json(route, { code: "OPERATION_EXPIRED", detail: "期限切れです。" }, 410);
      if (scenario === "conflict") return json(route, { code: "VERSION_CONFLICT", detail: "競合しました。" }, 409);
      if (scenario === "degraded") return json(route, { code: "SERVICE_UNAVAILABLE", detail: "一時的に利用できません。" }, 503);
      return json(route, [{ requestId: ids.request, status: "submitted", submittedAt: "2026-09-02T09:18:00Z" }]);
    }
    if (path.endsWith("/services") && method === "GET") return json(route, [{ serviceId: ids.service, slug: "video-package", status: "published", content: { title: "映像パッケージ" } }]);
    if (path.endsWith("/workflow-templates") && method === "GET") return json(route, [{ templateId: ids.workflow, versionId: ids.workflow, version: 3, name: "映像制作", status: "published", checkpointCount: 5 }]);
    if (path.endsWith("/projects") && method === "GET") return json(route, [{ projectId: ids.project, status: "active", waitingOn: "CLIENT", currentCheckpointId: ids.checkpoint, version: 4 }]);
    if ((path.includes(`/projects/${ids.project}`) || path.includes(`/projects/${ids.access}`)) && method === "GET") return json(route, { projectId: ids.project, status: "active", waitingOn: "CLIENT", currentCheckpointId: ids.checkpoint, version: 4 });
    if (path === "/notifications" && method === "GET") return json(route, [{ id: ids.notification, category: "activity", templateKey: "checkpoint_ready", data: {}, createdAt: "2026-09-02T11:20:00Z", readAt: null }]);
    if (path === "/public/creators/northline") return json(route, { slug: "northline", displayName: "Northline Studio", bio: "映像制作の受付と進行を公開しています。", categories: ["映像"], intakeStatus: "open" });
    if (path === "/public/services/video-package/intake-form") return json(route, { schema: { type: "object" }, privacyTextVersion: "privacy-v1" });
    if (path === "/public/services/video-package" && method === "GET") return json(route, { slug: "video-package", content: { title: "映像パッケージ", summary: "構成から最終書き出しまでを段階管理します。", revisionPolicy: "2ラウンド", deliverables: ["MP4", "WebM"] } });
    if (path === "/auth/factors") return json(route, [{ credentialId: ids.account, type: "passkey", status: "active" }]);
    if (path.endsWith("/billing/summary")) return json(route, { planKey: "studio", status: "active", currentPeriodEnd: "2026-10-02T00:00:00Z", limits: { projects: 20, members: 10 } });
    if (path === "/admin/reports") return json(route, [{ id: ids.report, workspaceId: ids.workspace, subjectType: "file", subjectId: ids.project, reasonCode: "unsafe_file", status: "open", createdAt: "2026-09-02T10:14:00Z" }]);
    if (method === "POST" && path === "/public/services/video-package/requests") return json(route, { requestId: ids.request, requestAccessId: ids.access }, 201);
    if (method === "POST" && path.endsWith("/billing/portal-sessions")) return json(route, { url: "https://billing.invalid/session" }, 201);
    if (["POST", "PATCH", "PUT", "DELETE"].includes(method)) return json(route, { id: ids.report }, method === "POST" ? 201 : 200);
    return json(route, { code: "NOT_FOUND", detail: `未定義のテスト経路: ${method} ${path}` }, 404);
  });
}
