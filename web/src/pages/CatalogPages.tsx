import { useState } from "react";
import { Copy, Plus } from "lucide-react";
import { ActionResult, Badge, PageHeader, ScenarioBoundary } from "../components/ProductUi";

export function ServicesPage() {
  const [created, setCreated] = useState(false);
  return <section className="page-enter"><PageHeader title="サービス" detail="公開中の内容と受付状態" action={<button className="primary-button" onClick={() => setCreated(true)}><Plus size={16} />サービスを作成</button>} />
    {created && <ActionResult>下書きを作成しました</ActionResult>}
    <ScenarioBoundary emptyTitle="サービスはまだありません"><div className="card-grid">
      <article className="data-card"><header><h2>映像パッケージ</h2><Badge tone="success">公開中</Badge></header><dl><div><dt>受付</dt><dd>受付中</dd></div><div><dt>ワークフロー</dt><dd>映像制作 v3</dd></div></dl><button className="secondary-button"><Copy size={15} />複製</button></article>
      <article className="data-card"><header><h2>キービジュアル</h2><Badge>下書き</Badge></header><dl><div><dt>受付</dt><dd>非公開</dd></div><div><dt>ワークフロー</dt><dd>静止画制作 v2</dd></div></dl><button className="secondary-button">編集</button></article>
    </div></ScenarioBoundary>
  </section>;
}

export function WorkflowsPage() {
  return <section className="page-enter"><PageHeader title="ワークフロー" detail="公開版は進行中案件から独立して固定されます。" action={<button className="primary-button"><Plus size={16} />新規作成</button>} />
    <ScenarioBoundary emptyTitle="ワークフローはまだありません"><div className="record-list">
      {[{ name: "映像制作", version: "v3", steps: 5 }, { name: "静止画制作", version: "v2", steps: 4 }].map((item) => <article className="record-row" key={item.name}><div><h2>{item.name}</h2><p>{item.steps}工程 · 公開版 {item.version}</p></div><Badge tone="success">公開中</Badge><button className="row-action">編集</button></article>)}
    </div></ScenarioBoundary>
  </section>;
}
