import { useEffect, useMemo, useState } from "react";
import { Navigate, Route, Routes, useLocation } from "react-router-dom";
import type { SessionSnapshot } from "../domain/session";
import { SessionService } from "../domain/session";
import { DemoSessionRepository } from "../infrastructure/DemoSessionRepository";
import { AppShell } from "../components/AppShell";
import { DashboardPage } from "../pages/DashboardPage";
import { RequestFormPage } from "../pages/RequestFormPage";
import { SignInPage } from "../pages/SignInPage";
import { SurfacePage } from "../pages/SurfacePage";
import { StatusView } from "../components/StatusView";

const initialSession: SessionSnapshot = { status: "initializing" };

export function App() {
  const service = useMemo(() => new SessionService(new DemoSessionRepository()), []);
  const [session, setSession] = useState(initialSession);
  const location = useLocation();

  useEffect(() => {
    let active = true;
    service.initialize().then((next) => active && setSession(next));
    return () => { active = false; };
  }, [service]);

  const publicRoute = !location.pathname.startsWith("/app") && !location.pathname.startsWith("/admin");
  if (!publicRoute && session.status === "initializing") return <StatusView state="loading" />;
  if (!publicRoute && session.status === "degraded") return <StatusView state="degraded" />;
  if (!publicRoute && session.status === "anonymous") return <Navigate to="/login" replace />;

  return (
    <Routes>
      <Route path="/login" element={<SignInPage />} />
      <Route path="/register" element={<SignInPage mode="register" />} />
      <Route path="/recover" element={<SignInPage mode="recover" />} />
      <Route path="/auth/link" element={<StatusView state="link" />} />
      <Route path="/creators/:slug" element={<SurfacePage title="公開プロフィール" />} />
      <Route path="/services/:slug" element={<SurfacePage title="サービス詳細" />} />
      <Route path="/services/:slug/request" element={<RequestFormPage />} />
      <Route path="/portal/projects/:projectAccessId" element={<SurfacePage title="プロジェクトポータル" />} />
      <Route element={<AppShell session={session} />}>
        <Route path="/app" element={<DashboardPage />} />
        <Route path="/app/requests" element={<SurfacePage title="受付" />} />
        <Route path="/app/services" element={<SurfacePage title="サービス" />} />
        <Route path="/app/workflows" element={<SurfacePage title="ワークフロー" />} />
        <Route path="/app/projects/:projectId" element={<SurfacePage title="プロジェクト" />} />
        <Route path="/app/settings/:section" element={<SurfacePage title="設定" />} />
        <Route path="/admin/:section" element={<SurfacePage title="運用管理" />} />
      </Route>
      <Route path="*" element={<StatusView state="not-found" />} />
    </Routes>
  );
}
