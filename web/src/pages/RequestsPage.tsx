import { useCallback, useMemo, useState } from "react";
import { Filter } from "lucide-react";
import { useApiEnvironment, useApiResource } from "../api/ApiContext";
import type { RequestSummary } from "../api/ApiClient";
import { ActionResult, Badge, PageHeader, ResourceBoundary, SegmentedTabs } from "../components/ProductUi";

const statusLabels: Record<string, string> = { submitted: "未対応", screening: "確認中", clarification: "回答待ち", accepted: "受付済み", declined: "見送り", withdrawn: "取下げ" };
const statusTones = { submitted: "warning", screening: "info", accepted: "success", declined: "danger" } as const;

export function RequestsPage() {
  const { client, workspaceId } = useApiEnvironment();
  const loader = useCallback(() => workspaceId ? client.listRequests(workspaceId) : Promise.reject(new Error("ワークスペースが未設定です。")), [client, workspaceId]);
  const resource = useApiResource(loader, [loader]);
  const [filter, setFilter] = useState("すべて");
  const [result, setResult] = useState("");
  const [failed, setFailed] = useState(false);
  const classify = async (item: RequestSummary) => {
    if (!workspaceId) return;
    setResult("");
    try { await client.classifyRequest(workspaceId, item.requestId, "screening", "creator_review"); setFailed(false); setResult("確認中に更新しました"); resource.reload(); }
    catch (error) { setFailed(true); setResult(error instanceof Error ? error.message : "更新できませんでした。"); }
  };
  return <section className="page-enter">
    <PageHeader title="受付" detail="受付状態を確認し、次の処理へ進めます。" action={<button className="secondary-button" type="button" onClick={resource.reload}><Filter size={16} />表示を更新</button>} />
    {result && <ActionResult tone={failed ? "danger" : "success"}>{result}</ActionResult>}
    <SegmentedTabs label="受付の絞り込み" tabs={["すべて", "未対応", "確認中"]} active={filter} onChange={setFilter} />
    <ResourceBoundary resource={resource} empty={(items) => items.length === 0} emptyTitle="受付はまだありません">{(items) => <RequestRows items={items} filter={filter} onClassify={classify} />}</ResourceBoundary>
  </section>;
}

function RequestRows({ items, filter, onClassify }: { readonly items: RequestSummary[]; readonly filter: string; readonly onClassify: (item: RequestSummary) => void }) {
  const visible = useMemo(() => filter === "すべて" ? items : items.filter((item) => statusLabels[item.status] === filter), [filter, items]);
  if (visible.length === 0) return <section className="inline-state" role="status"><h2>該当する受付はありません</h2></section>;
  return <div className="record-list">{visible.map((item) => <article className="record-row" key={item.requestId}>
    <div><span className="record-id">{item.requestId}</span><h2>制作依頼</h2><p><time dateTime={item.submittedAt}>{new Intl.DateTimeFormat("ja-JP", { dateStyle: "medium", timeStyle: "short" }).format(new Date(item.submittedAt))}</time></p></div>
    <Badge tone={statusTones[item.status as keyof typeof statusTones] ?? "neutral"}>{statusLabels[item.status] ?? item.status}</Badge>
    {item.status === "submitted" && <button className="row-action" type="button" onClick={() => onClassify(item)}>確認を開始</button>}
  </article>)}</div>;
}
