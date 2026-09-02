import { useCallback, useState, type FormEvent } from "react";
import { FileUp, Send } from "lucide-react";
import { useParams } from "react-router-dom";
import { useApiEnvironment, useApiResource } from "../api/ApiContext";
import type { ProjectSummary } from "../api/ApiClient";
import { ActionResult, Badge, PageHeader, ResourceBoundary, SegmentedTabs } from "../components/ProductUi";

const tabs = ["概要", "進捗", "ファイル"] as const;
type ActionFeedback = { readonly message: string; readonly tone: "success" | "danger" };

export function ProjectPage() {
  const { projectId = "" } = useParams();
  const { client, workspaceId } = useApiEnvironment();
  const loader = useCallback(() => workspaceId && projectId ? client.getProject(workspaceId, projectId) : Promise.reject(new Error("案件識別子が未設定です。")), [client, projectId, workspaceId]);
  const resource = useApiResource(loader, [loader]);
  const [active, setActive] = useState<string>("概要");
  const [result, setResult] = useState<ActionFeedback | null>(null);
  return <section className="page-enter"><PageHeader title="プロジェクト" detail={projectId} action={resource.status === "ready" ? <Badge tone={resource.data.status === "active" ? "info" : "neutral"}>{resource.data.status}</Badge> : undefined} />
    <SegmentedTabs label="プロジェクト表示" tabs={tabs} active={active} onChange={setActive} />
    {result && <ActionResult tone={result.tone}>{result.message}</ActionResult>}
    <ResourceBoundary resource={resource}>{(project) => <>
      {active === "概要" && <Overview project={project} />}
      {active === "進捗" && <Progress project={project} onResult={setResult} onReload={resource.reload} />}
      {active === "ファイル" && <UploadPanel project={project} onResult={setResult} />}
    </>}</ResourceBoundary>
  </section>;
}

function Overview({ project }: { readonly project: ProjectSummary }) {
  return <div className="project-layout"><section className="stage-list"><h2>進行状況</h2><article className="stage stage--active"><span>{project.version}</span><div><strong>{project.status}</strong><small>待機先: {project.waitingOn}</small></div></article></section><aside className="facts-panel"><h2>現在状態</h2><dl><div><dt>版</dt><dd>{project.version}</dd></div><div><dt>待機先</dt><dd>{project.waitingOn}</dd></div><div><dt>工程ID</dt><dd className="record-id">{project.currentCheckpointId ?? "未設定"}</dd></div></dl></aside></div>;
}

function Progress({ project, onResult, onReload }: { readonly project: ProjectSummary; readonly onResult: (value: ActionFeedback | null) => void; readonly onReload: () => void }) {
  const { client, workspaceId } = useApiEnvironment();
  const [text, setText] = useState("");
  const [clientVisible, setClientVisible] = useState(true);
  const [busy, setBusy] = useState(false);
  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (!workspaceId || !project.currentCheckpointId || !text.trim()) return;
    setBusy(true); onResult(null);
    try { await client.publishProgress(workspaceId, project.currentCheckpointId, text.trim(), clientVisible); setText(""); onResult({ message: "進捗を記録しました", tone: "success" }); onReload(); }
    catch (error) { onResult({ message: error instanceof Error ? error.message : "進捗を記録できませんでした。", tone: "danger" }); }
    finally { setBusy(false); }
  };
  if (!project.currentCheckpointId) return <section className="inline-state" role="status"><h2>進行中の工程はありません</h2></section>;
  return <form className="composer" onSubmit={submit}><label htmlFor="progress">進捗</label><textarea id="progress" value={text} onChange={(event) => setText(event.target.value)} required maxLength={20000} /><label className="check-row"><input type="checkbox" checked={clientVisible} onChange={(event) => setClientVisible(event.target.checked)} />依頼者へ公開</label><button className="primary-button" type="submit" disabled={busy || !text.trim()} aria-busy={busy}><Send size={16} />{busy ? "送信中…" : "投稿"}</button></form>;
}

function UploadPanel({ project, onResult }: { readonly project: ProjectSummary; readonly onResult: (value: ActionFeedback | null) => void }) {
  const { client, workspaceId } = useApiEnvironment();
  const [busy, setBusy] = useState(false);
  const select = async (file: File | undefined) => {
    if (!file || !workspaceId) return;
    setBusy(true); onResult(null);
    try { await client.initiateUpload(workspaceId, project.projectId, file); onResult({ message: "アップロード枠を作成しました", tone: "success" }); }
    catch (error) { onResult({ message: error instanceof Error ? error.message : "アップロードを開始できませんでした。", tone: "danger" }); }
    finally { setBusy(false); }
  };
  return <div className="upload-zone"><FileUp aria-hidden="true" /><h2>ファイルを追加</h2><p>最大4GB。選択後にアップロード枠を作成します。</p><label className="primary-button">{busy ? "準備中…" : "ファイルを選択"}<input className="visually-hidden" type="file" disabled={busy} onChange={(event) => select(event.target.files?.[0])} /></label></div>;
}
