import { useCallback } from "react";
import { ArrowRight, BriefcaseBusiness } from "lucide-react";
import { Link } from "react-router-dom";
import { useApiEnvironment, useApiResource } from "../api/ApiContext";
import { Badge, PageHeader, ResourceBoundary } from "../components/ProductUi";

export function ProjectsPage() {
  const { client, workspaceId } = useApiEnvironment();
  const loader = useCallback(() => workspaceId ? client.listProjects(workspaceId) : Promise.reject(new Error("ワークスペースが未設定です。")), [client, workspaceId]);
  const resource = useApiResource(loader, [loader]);
  return <section className="page-enter"><PageHeader title="プロジェクト" detail="合意済み案件の現在状態" />
    <ResourceBoundary resource={resource} empty={(items) => items.length === 0} emptyTitle="プロジェクトはまだありません">{(items) => <div className="record-list">{items.map((item) => <article className="record-row" key={item.projectId}><BriefcaseBusiness /><div><span className="record-id">{item.projectId}</span><h2>プロジェクト</h2><p>待機先: {item.waitingOn}</p></div><Badge tone={item.status === "active" ? "info" : item.status === "completed" ? "success" : "neutral"}>{item.status}</Badge><Link className="row-action" to={`/app/projects/${item.projectId}`}>開く<ArrowRight size={15} /></Link></article>)}</div>}</ResourceBoundary>
  </section>;
}
