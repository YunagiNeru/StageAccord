import { useCallback } from "react";
import { ArrowRight, Bell, BriefcaseBusiness, Inbox } from "lucide-react";
import { Link } from "react-router-dom";
import { useApiEnvironment, useApiResource } from "../api/ApiContext";
import type { NotificationSummary, ProjectSummary, RequestSummary } from "../api/ApiClient";
import { PageHeader, ResourceBoundary } from "../components/ProductUi";

interface DashboardData {
  readonly requests: RequestSummary[];
  readonly projects: ProjectSummary[];
  readonly notifications: NotificationSummary[];
}

export function DashboardPage() {
  const { client, workspaceId } = useApiEnvironment();
  const loader = useCallback(async (): Promise<DashboardData> => {
    if (!workspaceId) throw new Error("ワークスペースが未設定です。");
    const [requests, projects, notifications] = await Promise.all([client.listRequests(workspaceId), client.listProjects(workspaceId), client.listNotifications()]);
    return { requests, projects, notifications };
  }, [client, workspaceId]);
  const resource = useApiResource(loader, [loader]);
  return <section className="dashboard page-enter">
    <PageHeader title="概要" detail={new Intl.DateTimeFormat("ja-JP", { dateStyle: "full" }).format(new Date())} action={<Link className="primary-button" to="/app/requests">受付を確認<ArrowRight size={16} /></Link>} />
    <ResourceBoundary resource={resource}>{(data) => <>
      <section className="signal-strip" aria-label="現在の状況">
        <article><span><Inbox size={17} />未対応の受付</span><strong>{data.requests.filter((item) => item.status === "submitted").length}</strong><small>全{data.requests.length}件</small></article>
        <article><span><BriefcaseBusiness size={17} />進行中</span><strong>{data.projects.filter((item) => item.status === "active").length}</strong><small>全{data.projects.length}件</small></article>
        <article><span><Bell size={17} />未読通知</span><strong>{data.notifications.filter((item) => !item.readAt).length}</strong><small>全{data.notifications.length}件</small></article>
      </section>
      <div className="dashboard-grid"><section className="activity-panel"><header><h2>最近の通知</h2></header>{data.notifications.length === 0 ? <p className="field-help">新しい通知はありません。</p> : <ol>{data.notifications.slice(0, 5).map((item) => <li key={item.id}><span className={`activity-dot ${item.readAt ? "activity-dot--complete" : "activity-dot--new"}`} aria-hidden="true" /><div><strong>{item.templateKey}</strong><span>{item.category}</span></div><time dateTime={item.createdAt}>{new Intl.DateTimeFormat("ja-JP", { dateStyle: "short", timeStyle: "short" }).format(new Date(item.createdAt))}</time></li>)}</ol>}</section>
      <aside className="next-panel"><h2>案件</h2><strong>{data.projects.length}件</strong><Link to={data.projects[0] ? `/app/projects/${data.projects[0].projectId}` : "/app/requests"}>{data.projects[0] ? "先頭の案件を開く" : "受付を確認"}<ArrowRight size={16} /></Link></aside></div>
    </>}</ResourceBoundary>
  </section>;
}
