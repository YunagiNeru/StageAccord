import { ApiClient, ApiFailure } from "./ApiClient";

afterEach(() => { vi.unstubAllGlobals(); document.cookie = "XSRF-TOKEN=; Max-Age=0"; });

describe("ApiClient", () => {
  it("Cookie認証付きでAPIを呼び出す", async () => {
    const fetcher = vi.fn(async () => new Response(JSON.stringify([]), { status: 200 }));
    vi.stubGlobal("fetch", fetcher);
    await new ApiClient("/api/v1").listRequests("workspace-id");
    expect(fetcher).toHaveBeenCalledWith("/api/v1/workspaces/workspace-id/requests", expect.objectContaining({ credentials: "include", method: "GET" }));
  });

  it.each([[403, "forbidden"], [409, "conflict"], [410, "expired"], [503, "degraded"]] as const)("HTTP %sを%s状態へ変換する", async (status, kind) => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({ code: "TEST", detail: "検証用" }), { status, headers: { "Content-Type": "application/problem+json" } })));
    await expect(new ApiClient().listNotifications()).rejects.toMatchObject({ kind, status, code: "TEST" } satisfies Partial<ApiFailure>);
  });

  it("通信不能を縮退状態へ変換する", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => { throw new TypeError("offline"); }));
    await expect(new ApiClient().listNotifications()).rejects.toMatchObject({ kind: "degraded", status: 0 } satisfies Partial<ApiFailure>);
  });

  it("変更要求へCSRFトークンを付与する", async () => {
    document.cookie = "XSRF-TOKEN=csrf-test-token";
    const fetcher = vi.fn(async () => new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetcher);
    await new ApiClient().markNotificationRead("notification-id");
    expect(fetcher).toHaveBeenCalledWith("/api/v1/notifications/notification-id/reads", expect.objectContaining({ headers: expect.objectContaining({ "X-XSRF-TOKEN": "csrf-test-token" }) }));
  });
});
