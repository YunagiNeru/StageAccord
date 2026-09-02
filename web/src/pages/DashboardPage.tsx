import { ArrowRight, CalendarDays, CircleCheck, Clock3, Inbox } from "lucide-react";
import { Link } from "react-router-dom";

const activity = [
  { label: "要件確認の回答待ち", project: "ブランドサイト制作", time: "今日 14:30", tone: "waiting" },
  { label: "初稿を共有しました", project: "採用資料デザイン", time: "昨日 18:12", tone: "complete" },
  { label: "新しい依頼が届きました", project: "新規受付", time: "昨日 10:04", tone: "new" },
] as const;

export function DashboardPage() {
  return (
    <div className="dashboard page-enter">
      <header className="page-heading"><div><h1>概要</h1><p>2026年9月2日 水曜日</p></div><Link className="primary-button" to="/app/requests">受付を確認<ArrowRight size={16} /></Link></header>
      <section className="signal-strip" aria-label="現在の状況">
        <article><span><Inbox size={17} />未対応の受付</span><strong>4</strong><small>うち本日期限 1件</small></article>
        <article><span><Clock3 size={17} />進行中</span><strong>7</strong><small>確認待ち 2件</small></article>
        <article><span><CalendarDays size={17} />今週の期限</span><strong>3</strong><small>次回 9月3日</small></article>
      </section>
      <div className="dashboard-grid">
        <section className="activity-panel"><header><h2>最近の動き</h2><Link to="/app/projects/demo-project">すべて表示</Link></header><ol>{activity.map((item) => <li key={item.label}><span className={`activity-dot activity-dot--${item.tone}`} aria-hidden="true" /><div><strong>{item.label}</strong><span>{item.project}</span></div><time>{item.time}</time></li>)}</ol></section>
        <aside className="next-panel"><h2>次の予定</h2><time dateTime="2026-09-03T13:00"><span>9月3日</span><strong>13:00</strong></time><div><strong>デザイン確認</strong><span>ブランドサイト制作</span></div><Link to="/app/projects/demo-project">案件を開く<ArrowRight size={16} /></Link><p><CircleCheck size={16} />今日の期限はありません</p></aside>
      </div>
    </div>
  );
}
