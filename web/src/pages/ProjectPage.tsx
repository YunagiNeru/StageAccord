import { useRef, useState } from "react";
import { Check, Download, FileCheck2, MessageSquare, Send, Upload } from "lucide-react";
import { ActionResult, Badge, PageHeader, ScenarioBoundary, SegmentedTabs } from "../components/ProductUi";

const tabs = ["概要", "タイムライン", "ファイル", "承認", "納品"] as const;

export function ProjectPage() {
  const [active, setActive] = useState<string>("概要");
  const [result, setResult] = useState("");
  const dialog = useRef<HTMLDialogElement>(null);
  const accept = () => { setResult("確認対象 v4 を承認しました"); dialog.current?.close(); };
  return <section className="page-enter"><PageHeader title="ブランドサイト制作" detail="PRJ-2026-018 · 依頼者の確認待ち" action={<Badge tone="warning">確認待ち</Badge>} />
    <SegmentedTabs label="プロジェクト表示" tabs={tabs} active={active} onChange={setActive} />
    {result && <ActionResult>{result}</ActionResult>}
    <ScenarioBoundary>{active === "概要" && <Overview />}{active === "タイムライン" && <Timeline onPost={() => setResult("進捗を依頼者へ共有しました")} />}{active === "ファイル" && <Files />}{active === "承認" && <Approval onOpen={() => dialog.current?.showModal()} />}{active === "納品" && <Delivery onReceive={() => setResult("納品パッケージを受領済みにしました")} />}</ScenarioBoundary>
    <dialog ref={dialog} className="confirm-dialog" onClick={(event) => event.target === dialog.current && dialog.current.close()}>
      <form method="dialog"><Badge tone="warning">明示承認</Badge><h2>確認対象 v4 を承認しますか</h2><p>承認後は次の工程へ進みます。差し替え版にはこの承認は引き継がれません。</p><div className="dialog-actions"><button className="secondary-button" value="cancel">戻る</button><button className="primary-button" value="confirm" onClick={(event) => { event.preventDefault(); accept(); }}><Check size={16} />承認する</button></div></form>
    </dialog>
  </section>;
}

function Overview() { return <div className="project-layout"><section className="stage-list"><h2>進行状況</h2>{["要件確認", "構成", "デザイン確認", "実装", "納品"].map((name, index) => <article key={name} className={index < 2 ? "stage stage--done" : index === 2 ? "stage stage--active" : "stage"}><span>{index < 2 ? <Check size={15} /> : index + 1}</span><div><strong>{name}</strong><small>{index === 2 ? "依頼者の確認期限 9月5日" : index < 2 ? "完了" : "未着手"}</small></div></article>)}</section><aside className="facts-panel"><h2>現在の合意</h2><dl><div><dt>合意版</dt><dd>v2</dd></div><div><dt>納品予定</dt><dd>9月18日</dd></div><div><dt>修正残数</dt><dd>2回</dd></div><div><dt>待機先</dt><dd>依頼者</dd></div></dl></aside></div>; }

function Timeline({ onPost }: { readonly onPost: () => void }) {
  const [text, setText] = useState("");
  return <div className="timeline-layout"><ol className="timeline"><li><span /><article><header><strong>デザイン確認用 v4</strong><time>今日 11:20</time></header><p>主要ページのレイアウトを更新しました。</p><Badge tone="info">依頼者に公開</Badge></article></li><li><span /><article><header><strong>コメント 2件</strong><time>昨日 16:05</time></header><p>確認対象 v3 に紐づく履歴です。</p></article></li></ol><form className="composer" onSubmit={(event) => { event.preventDefault(); if (text.trim()) { onPost(); setText(""); } }}><label htmlFor="progress">進捗を共有</label><textarea id="progress" value={text} onChange={(e) => setText(e.target.value)} required /><label className="check-row"><input type="checkbox" defaultChecked />依頼者へ公開</label><button className="primary-button"><Send size={16} />投稿</button></form></div>;
}

function Files() { return <div className="file-panel"><div className="upload-zone"><Upload aria-hidden="true" /><h2>ファイルを追加</h2><p>最大4GB。アップロード後に安全検査を行います。</p><button className="primary-button">ファイルを選択</button></div><div className="record-list"><article className="record-row"><FileCheck2 /><div><h2>design-v4.pdf</h2><p>18.4 MB · SHA-256確認済み</p></div><Badge tone="success">検査済み</Badge><button className="row-action"><Download size={15} />取得</button></article><article className="record-row"><FileCheck2 /><div><h2>preview-v5.mp4</h2><p>1.2 GB · 全文を検査中</p></div><Badge tone="info">検査中</Badge></article></div></div>; }

function Approval({ onOpen }: { readonly onOpen: () => void }) { return <div className="approval-panel"><section><Badge tone="info">確認対象 v4</Badge><h2>デザイン確認</h2><p>承認者 2名のうち1名が承認済みです。</p><div className="approval-people"><span><Check size={15} />佐藤 玲 · 承認済み</span><span><MessageSquare size={15} />高橋 奏 · 未回答</span></div></section><button className="primary-button" onClick={onOpen}>対象版を確認して承認</button></div>; }

function Delivery({ onReceive }: { readonly onReceive: () => void }) { return <div className="delivery-panel"><header><div><Badge tone="success">固定済み</Badge><h2>最終納品 #1</h2></div><span>2026年9月18日</span></header><ul><li><FileCheck2 /><span><strong>final-master.mp4</strong><small>3.4 GB · v6</small></span></li><li><FileCheck2 /><span><strong>credits.txt</strong><small>2 KB · v1</small></span></li></ul><dl><div><dt>利用条件</dt><dd>合意版 v2 に準拠</dd></div><div><dt>クレジット</dt><dd>記載不要</dd></div></dl><button className="primary-button" onClick={onReceive}>受領する</button></div>; }
