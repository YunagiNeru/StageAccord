import { useCallback, useRef, useState, type FormEvent } from "react";
import { Download, ShieldCheck } from "lucide-react";
import { useParams } from "react-router-dom";
import { useApiEnvironment, useApiResource } from "../api/ApiContext";
import { ActionResult, Badge, PageHeader, ResourceBoundary } from "../components/ProductUi";

type ActionFeedback = { readonly message: string; readonly tone: "success" | "danger" };

export function SettingsPage() {
  const { section = "notifications" } = useParams();
  const [result, setResult] = useState<ActionFeedback | null>(null);
  const titles: Record<string, string> = { notifications: "通知設定", billing: "請求", privacy: "データとプライバシー", security: "セキュリティ" };
  return <section className="page-enter"><PageHeader title={titles[section] ?? "設定"} />{result && <ActionResult tone={result.tone}>{result.message}</ActionResult>}
    {section === "notifications" && <NotificationSettings onResult={setResult} />}
    {section === "billing" && <BillingSettings onResult={setResult} />}
    {section === "privacy" && <PrivacySettings onResult={setResult} />}
    {section === "security" && <SecuritySettings />}
    {!titles[section] && <p>設定項目が見つかりません。</p>}
  </section>;
}

function NotificationSettings({ onResult }: { readonly onResult: (value: ActionFeedback) => void }) {
  const { client, workspaceId } = useApiEnvironment();
  const [mode, setMode] = useState("digest");
  const submit = async (event: FormEvent) => { event.preventDefault(); if (!workspaceId) return; try { await client.updateNotificationPreference(workspaceId, mode); onResult({ message: "通知設定を保存しました", tone: "success" }); } catch (error) { onResult({ message: error instanceof Error ? error.message : "保存できませんでした。", tone: "danger" }); } };
  return <form className="settings-form" onSubmit={submit}><section><header><h2>セキュリティ</h2><Badge tone="info">必須</Badge></header><label className="check-row"><input type="checkbox" checked disabled />メール</label><label className="check-row"><input type="checkbox" checked disabled />アプリ内</label></section><section><header><h2>案件の更新</h2></header><label htmlFor="activity-mode">配信方法</label><select id="activity-mode" value={mode} onChange={(event) => setMode(event.target.value)}><option value="immediate">すぐに通知</option><option value="digest">まとめて通知</option><option value="disabled">停止</option></select></section><button className="primary-button">保存</button></form>;
}

function BillingSettings({ onResult }: { readonly onResult: (value: ActionFeedback) => void }) {
  const { client, workspaceId } = useApiEnvironment();
  const loader = useCallback(() => workspaceId ? client.getBillingSummary(workspaceId) : Promise.reject(new Error("ワークスペースが未設定です。")), [client, workspaceId]);
  const resource = useApiResource(loader, [loader]);
  const openPortal = async () => { if (!workspaceId) return; try { const location = await client.createBillingPortal(workspaceId); window.location.assign(location.url); } catch (error) { onResult({ message: error instanceof Error ? error.message : "請求ポータルを開けませんでした。", tone: "danger" }); } };
  return <ResourceBoundary resource={resource}>{(billing) => <div className="settings-form"><section><header><div><h2>{billing.planKey ?? "プラン未設定"}</h2>{billing.currentPeriodEnd && <p>更新日 {new Intl.DateTimeFormat("ja-JP").format(new Date(billing.currentPeriodEnd))}</p>}</div><Badge tone={billing.status === "active" ? "success" : "warning"}>{billing.status}</Badge></header>{billing.limits && <dl>{Object.entries(billing.limits).map(([key, value]) => <div key={key}><dt>{key}</dt><dd>{value}</dd></div>)}</dl>}<button className="secondary-button" type="button" onClick={openPortal}>請求ポータルを開く</button></section></div>}</ResourceBoundary>;
}

function PrivacySettings({ onResult }: { readonly onResult: (value: ActionFeedback) => void }) {
  const { client, workspaceId } = useApiEnvironment();
  const dialog = useRef<HTMLDialogElement>(null);
  const exportData = async () => { if (!workspaceId) return; try { const item = await client.requestDataExport(workspaceId); onResult({ message: `エクスポートを作成しました: ${item.id}`, tone: "success" }); } catch (error) { onResult({ message: error instanceof Error ? error.message : "作成できませんでした。", tone: "danger" }); } };
  const deleteWorkspace = async () => { if (!workspaceId) return; try { const item = await client.requestWorkspaceDeletion(workspaceId); dialog.current?.close(); onResult({ message: `削除要求を受け付けました: ${item.id}`, tone: "success" }); } catch (error) { dialog.current?.close(); onResult({ message: error instanceof Error ? error.message : "削除要求を開始できませんでした。", tone: "danger" }); } };
  return <div className="settings-form"><section><header><h2>データの持ち出し</h2></header><button className="secondary-button" type="button" onClick={exportData}><Download size={16} />エクスポートを作成</button></section><section className="danger-zone"><header><h2>削除要求</h2></header><p>削除処理は法的保持と保存期限の制約を受けます。</p><button className="danger-button" type="button" onClick={() => dialog.current?.showModal()}>削除要求を開始</button></section><dialog ref={dialog} className="confirm-dialog"><form method="dialog"><h2>ワークスペースを削除しますか</h2><p>稼働データ、公開情報、認証セッションが削除対象になります。</p><div className="dialog-actions"><button className="secondary-button" value="cancel">戻る</button><button className="danger-button" value="confirm" onClick={(event) => { event.preventDefault(); void deleteWorkspace(); }}>削除要求を確定</button></div></form></dialog></div>;
}

function SecuritySettings() {
  const { client } = useApiEnvironment();
  const loader = useCallback(() => client.listAuthFactors(), [client]);
  const resource = useApiResource(loader, [loader]);
  return <ResourceBoundary resource={resource} empty={(items) => items.length === 0} emptyTitle="認証要素はありません">{(items) => <div className="settings-form"><section><header><div><h2>認証要素</h2><p>重要操作では再認証が必要です。</p></div><ShieldCheck /></header><dl>{items.map((item) => <div key={item.credentialId}><dt>{item.type}</dt><dd>{item.status}</dd></div>)}</dl></section></div>}</ResourceBoundary>;
}
