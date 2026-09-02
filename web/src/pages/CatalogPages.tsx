import { useCallback, useState } from "react";
import { Copy } from "lucide-react";
import { useApiEnvironment, useApiResource } from "../api/ApiContext";
import { ActionResult, Badge, PageHeader, ResourceBoundary } from "../components/ProductUi";

function text(value: unknown, fallback: string) { return typeof value === "string" && value.trim() ? value : fallback; }

export function ServicesPage() {
  const { client, workspaceId } = useApiEnvironment();
  const loader = useCallback(() => workspaceId ? client.listServices(workspaceId) : Promise.reject(new Error("ワークスペースが未設定です。")), [client, workspaceId]);
  const resource = useApiResource(loader, [loader]);
  const [result, setResult] = useState("");
  const [failed, setFailed] = useState(false);
  const clone = async (id: string, slug: string) => {
    if (!workspaceId) return;
    try { await client.cloneService(workspaceId, id, `${slug}-copy-${Date.now().toString().slice(-6)}`); setFailed(false); setResult("サービスの下書きを複製しました"); resource.reload(); }
    catch (error) { setFailed(true); setResult(error instanceof Error ? error.message : "複製できませんでした。"); }
  };
  return <section className="page-enter"><PageHeader title="サービス" detail="公開状態と受付内容" />{result && <ActionResult tone={failed ? "danger" : "success"}>{result}</ActionResult>}
    <ResourceBoundary resource={resource} empty={(items) => items.length === 0} emptyTitle="サービスはまだありません">{(items) => <div className="card-grid">{items.map((item) => <article className="data-card" key={item.serviceId}>
      <header><h2>{text(item.content?.title, item.slug)}</h2><Badge tone={item.status === "published" ? "success" : "neutral"}>{item.status === "published" ? "公開中" : "下書き"}</Badge></header>
      <dl><div><dt>識別子</dt><dd>{item.slug}</dd></div><div><dt>状態</dt><dd>{item.status}</dd></div></dl>
      <button className="secondary-button" type="button" onClick={() => clone(item.serviceId, item.slug)}><Copy size={15} />複製</button>
    </article>)}</div>}</ResourceBoundary>
  </section>;
}

export function WorkflowsPage() {
  const { client, workspaceId } = useApiEnvironment();
  const loader = useCallback(() => workspaceId ? client.listWorkflows(workspaceId) : Promise.reject(new Error("ワークスペースが未設定です。")), [client, workspaceId]);
  const resource = useApiResource(loader, [loader]);
  return <section className="page-enter"><PageHeader title="ワークフロー" detail="公開版は進行中案件から独立して固定されます。" />
    <ResourceBoundary resource={resource} empty={(items) => items.length === 0} emptyTitle="ワークフローはまだありません">{(items) => <div className="record-list">{items.map((item) => <article className="record-row" key={item.versionId}><div><h2>{item.name}</h2><p>{item.checkpointCount}工程 · v{item.version}</p></div><Badge tone={item.status === "published" ? "success" : "neutral"}>{item.status}</Badge></article>)}</div>}</ResourceBoundary>
  </section>;
}
