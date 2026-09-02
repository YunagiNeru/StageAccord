import { useState } from "react";
import { LockKeyhole, OctagonX, ShieldAlert } from "lucide-react";
import { useParams } from "react-router-dom";
import { ActionResult, Badge, PageHeader, ScenarioBoundary } from "../components/ProductUi";

export function AdminPage() {
  const { section = "reports" } = useParams();
  const [result, setResult] = useState("");
  return <section className="page-enter"><PageHeader title="運用管理" detail="VPN・MFA・目的限定アクセス" action={<Badge tone="warning">管理者</Badge>} />{result && <ActionResult>{result}</ActionResult>}<ScenarioBoundary>
    {section === "reports" && <div className="record-list"><article className="record-row"><ShieldAlert /><div><h2>危険なファイル</h2><p>RPT-218 · 9月2日 10:14</p></div><Badge tone="warning">調査中</Badge><button className="row-action" onClick={() => setResult("通報を調査中に更新しました")}>対応</button></article></div>}
    {section === "support" && <section className="admin-panel"><LockKeyhole /><div><h2>支援アクセス</h2><p>対象案件・チケット・目的・操作を限定し、別担当者の承認後60分だけ有効です。</p></div><button className="primary-button" onClick={() => setResult("支援アクセス申請を作成しました")}>申請</button></section>}
    {section === "kill-switches" && <section className="admin-panel admin-panel--critical"><OctagonX /><div><h2>機能停止</h2><p>認証、受付、アップロード、重要な書き込みを個別に停止します。</p></div><button className="danger-button" onClick={() => setResult("アップロード停止の確認待ちです")}>停止を申請</button></section>}
  </ScenarioBoundary></section>;
}
