import { useState } from "react";
import { Download, ShieldCheck } from "lucide-react";
import { useParams } from "react-router-dom";
import { ActionResult, Badge, PageHeader, ScenarioBoundary } from "../components/ProductUi";

export function SettingsPage() {
  const { section = "notifications" } = useParams();
  const [saved, setSaved] = useState(false);
  const titles: Record<string, string> = { notifications: "通知設定", billing: "請求", privacy: "データとプライバシー", security: "セキュリティ" };
  return <section className="page-enter"><PageHeader title={titles[section] ?? "設定"} />{saved && <ActionResult>設定を保存しました</ActionResult>}<ScenarioBoundary>
    {section === "notifications" && <NotificationSettings onSave={() => setSaved(true)} />}
    {section === "billing" && <BillingSettings />}
    {section === "privacy" && <PrivacySettings onRequest={() => setSaved(true)} />}
    {section === "security" && <SecuritySettings />}
    {!titles[section] && <p>設定項目が見つかりません。</p>}
  </ScenarioBoundary></section>;
}

function NotificationSettings({ onSave }: { readonly onSave: () => void }) {
  return <form className="settings-form" onSubmit={(e) => { e.preventDefault(); onSave(); }}><section><header><h2>セキュリティ</h2><Badge tone="info">必須</Badge></header><label className="check-row"><input type="checkbox" checked disabled />メール</label><label className="check-row"><input type="checkbox" checked disabled />アプリ内</label></section><section><header><h2>案件の更新</h2></header><label htmlFor="activity-mode">配信方法</label><select id="activity-mode" defaultValue="digest"><option value="immediate">すぐに通知</option><option value="digest">まとめて通知</option><option value="disabled">停止</option></select></section><button className="primary-button">保存</button></form>;
}

function BillingSettings() { return <div className="settings-form"><section><header><div><h2>Studio</h2><p>次回更新 2026年10月2日</p></div><Badge tone="success">有効</Badge></header><dl><div><dt>進行中案件</dt><dd>7 / 20</dd></div><div><dt>メンバー</dt><dd>4 / 10</dd></div><div><dt>保存容量</dt><dd>82 / 200 GB</dd></div></dl><button className="secondary-button">請求ポータルを開く</button></section></div>; }

function PrivacySettings({ onRequest }: { readonly onRequest: () => void }) { return <div className="settings-form"><section><header><h2>データの持ち出し</h2></header><button className="secondary-button"><Download size={16} />エクスポートを作成</button></section><section className="danger-zone"><header><h2>削除要求</h2></header><p>法的保持の対象を除き、キャッシュは24時間、稼働データは30日、通常バックアップは35日以内に失効します。</p><button className="danger-button" onClick={onRequest}>削除要求を開始</button></section></div>; }

function SecuritySettings() { return <div className="settings-form"><section><header><div><h2>認証要素</h2><p>重要操作では30分以内の再認証が必要です。</p></div><ShieldCheck /></header><dl><div><dt>パスキー</dt><dd>2件</dd></div><div><dt>TOTP</dt><dd>有効</dd></div><div><dt>回復コード</dt><dd>8件</dd></div></dl><button className="secondary-button">認証要素を管理</button></section></div>; }
