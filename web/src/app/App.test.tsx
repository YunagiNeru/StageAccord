import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { App } from "./App";

describe("App", () => {
  it("認証済みセッションで概要を表示する", async () => {
    render(<MemoryRouter initialEntries={["/app"]}><App /></MemoryRouter>);
    expect(await screen.findByRole("heading", { name: "概要" })).toBeInTheDocument();
    expect(screen.getByText("Northline Studio")).toBeInTheDocument();
  });

  it("不明な公開経路を404表示にする", async () => {
    render(<MemoryRouter initialEntries={["/unknown"]}><App /></MemoryRouter>);
    expect(await screen.findByRole("heading", { name: "ページが見つかりません" })).toBeInTheDocument();
  });
});
