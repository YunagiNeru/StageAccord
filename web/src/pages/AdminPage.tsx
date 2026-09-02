import { useCallback, useState } from "react";
import { LockKeyhole, OctagonX, ShieldAlert } from "lucide-react";
import { useParams } from "react-router-dom";
import { useApiEnvironment, useApiResource } from "../api/ApiContext";
import { ActionResult, Badge, PageHeader, ResourceBoundary } from "../components/ProductUi";

export function AdminPage() {
  const { section = "reports" } = useParams();
  const { client, workspaceId } = useApiEnvironment();
  const [result, setResult] = useState("");
  const [failed, setFailed] = useState(false);
  const reportLoader = useCallback(() => client.listReports(), [client]);
  const reports = useApiResource(reportLoader, [reportLoader]);
  const update = async (id: string) => { try { await client.updateReport(id, "investigating"); setFailed(false); setResult("通報を調査中に更新しました"); reports.reload(); } catch (error) { setFailed(true); setResult(error instanceof Error ? error.message : "更新できませんでした。"); } };
  const support = async () => { if (!workspaceId) return; try { const projects = await client.listProjects(workspaceId); if (!projects[0]) { setFailed(true); setResult("対象案件がありません。"); return; } await client.requestSupport(workspaceId, projects[0].projectId); setFailed(false); setResult("支援アクセス申請を作成しました"); } catch (error) { setFailed(true); setResult(error instanceof Error ? error.message : "申請できませんでした。"); } };
  const stop = async () => { if (!workspaceId) return; try { await client.activateKillSwitch(workspaceId, "uploads"); setFailed(false); setResult("アップロード停止の独立承認待ちです"); } catch (error) { setFailed(true); setResult(error instanceof Error ? error.message : "停止を申請できませんでした。"); } };
  return <section className="page-enter"><PageHeader title="運用管理" detail="目的限定アクセス" action={<Badge tone="warning">管理者</Badge>} />{result && <ActionResult tone={failed ? "danger" : "success"}>{result}</ActionResult>}
    {section === "reports" && <ResourceBoundary resource={reports} empty={(items) => items.length === 0} emptyTitle="通報はありません">{(items) => <div className="record-list">{items.map((item) => <article className="record-row" key={item.id}><ShieldAlert /><div><h2>{item.reasonCode}</h2><p>{item.id} · {new Intl.DateTimeFormat("ja-JP", { dateStyle: "short", timeStyle: "short" }).format(new Date(item.createdAt))}</p></div><Badge tone={item.status === "open" ? "warning" : "info"}>{item.status}</Badge>{item.status === "open" && <button className="row-action" type="button" onClick={() => update(item.id)}>対応</button>}</article>)}</div>}</ResourceBoundary>}
    {section === "support" && <section className="admin-panel"><LockKeyhole /><div><h2>支援アクセス</h2><p>対象案件・目的・操作を限定し、別担当者の承認後に有効化します。</p></div><button className="primary-button" type="button" onClick={support}>申請</button></section>}
    {section === "kill-switches" && <section className="admin-panel admin-panel--critical"><OctagonX /><div><h2>機能停止</h2><p>アップロードの書き込みを停止します。</p></div><button className="danger-button" type="button" onClick={stop}>停止を申請</button></section>}
  </section>;
}
