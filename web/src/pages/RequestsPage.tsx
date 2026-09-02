import { useState } from "react";
import { ArrowRight, Filter } from "lucide-react";
import { Link } from "react-router-dom";
import { ActionResult, Badge, PageHeader, ScenarioBoundary, SegmentedTabs } from "../components/ProductUi";

const requests = [
  { id: "REQ-1042", title: "配信オープニング映像", sender: "佐藤 玲", submitted: "今日 09:18", status: "未対応", tone: "warning" },
  { id: "REQ-1041", title: "イベント告知ビジュアル", sender: "高橋 奏", submitted: "昨日 17:42", status: "確認中", tone: "info" },
  { id: "REQ-1038", title: "番組ロゴリニューアル", sender: "合同会社みなも", submitted: "8月30日", status: "条件提示済み", tone: "success" },
] as const;

export function RequestsPage() {
  const [filter, setFilter] = useState("すべて");
  const [updated, setUpdated] = useState(false);
  const visible = filter === "すべて" ? requests : requests.filter((item) => item.status === filter);
  return <section className="page-enter">
    <PageHeader title="受付" detail="依頼内容を確認し、条件提示へ進めます。" action={<button className="secondary-button" type="button" onClick={() => setUpdated(true)}><Filter size={16} />表示を更新</button>} />
    {updated && <ActionResult>最新の受付状態です</ActionResult>}
    <SegmentedTabs label="受付の絞り込み" tabs={["すべて", "未対応", "確認中"]} active={filter} onChange={setFilter} />
    <ScenarioBoundary emptyTitle="該当する受付はありません">
      <div className="record-list">{visible.map((item) => <article className="record-row" key={item.id}>
        <div><span className="record-id">{item.id}</span><h2>{item.title}</h2><p>{item.sender} · {item.submitted}</p></div>
        <Badge tone={item.tone}>{item.status}</Badge>
        <Link className="row-action" to={`/app/projects/${item.id.toLowerCase()}`}>詳細<ArrowRight size={15} /></Link>
      </article>)}</div>
    </ScenarioBoundary>
  </section>;
}
