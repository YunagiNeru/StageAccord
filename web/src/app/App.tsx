import { useEffect, useMemo, useState } from "react";
import { Navigate, Route, Routes, useLocation } from "react-router-dom";
import type { SessionSnapshot } from "../domain/session";
import { SessionService } from "../domain/session";
import { DemoSessionRepository } from "../infrastructure/DemoSessionRepository";
import { AppShell } from "../components/AppShell";
import { DashboardPage } from "../pages/DashboardPage";
import { RequestsPage } from "../pages/RequestsPage";
import { ServicesPage, WorkflowsPage } from "../pages/CatalogPages";
import { ProjectPage } from "../pages/ProjectPage";
import { SettingsPage } from "../pages/SettingsPage";
import { AdminPage } from "../pages/AdminPage";
import { ClientPortalPage, CreatorProfilePage, ServiceDetailPage } from "../pages/PublicPages";
import { RequestFormPage } from "../pages/RequestFormPage";
import { SignInPage } from "../pages/SignInPage";
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
      <Route path="/creators/:slug" element={<CreatorProfilePage />} />
      <Route path="/services/:slug" element={<ServiceDetailPage />} />
      <Route path="/services/:slug/request" element={<RequestFormPage />} />
      <Route path="/portal/projects/:projectAccessId" element={<ClientPortalPage />} />
      <Route element={<AppShell session={session} />}>
        <Route path="/app" element={<DashboardPage />} />
        <Route path="/app/requests" element={<RequestsPage />} />
        <Route path="/app/services" element={<ServicesPage />} />
        <Route path="/app/workflows" element={<WorkflowsPage />} />
        <Route path="/app/projects/:projectId" element={<ProjectPage />} />
        <Route path="/app/settings/:section" element={<SettingsPage />} />
        <Route path="/admin/:section" element={<AdminPage />} />
      </Route>
      <Route path="*" element={<StatusView state="not-found" />} />
    </Routes>
  );
}
