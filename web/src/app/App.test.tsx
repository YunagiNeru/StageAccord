import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { App } from "./App";
import { ApiEnvironmentProvider } from "../api/ApiContext";

beforeEach(() => {
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    const body = url.endsWith("/auth/session")
      ? { sessionId: "00000000-0000-4000-8000-000000000002", accountId: "00000000-0000-4000-8000-000000000002", authStrength: "mfa", authenticatedAt: "2026-09-02T09:00:00Z", absoluteExpiresAt: "2026-09-09T09:00:00Z", revoked: false }
      : [];
    return new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });
  }));
});

afterEach(() => vi.unstubAllGlobals());

function TestApp({ path }: { readonly path: string }) {
  return <ApiEnvironmentProvider><MemoryRouter initialEntries={[path]}><App /></MemoryRouter></ApiEnvironmentProvider>;
}

describe("App", () => {
  it("認証済みセッションで概要を表示する", async () => {
    render(<TestApp path="/app" />);
    expect(await screen.findByRole("heading", { name: "概要" })).toBeInTheDocument();
    expect(screen.getByText("ワークスペース")).toBeInTheDocument();
  });

  it("不明な公開経路を404表示にする", async () => {
    render(<TestApp path="/unknown" />);
    expect(await screen.findByRole("heading", { name: "ページが見つかりません" })).toBeInTheDocument();
  });
});
