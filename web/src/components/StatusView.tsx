import { AlertTriangle, LoaderCircle, SearchX } from "lucide-react";
import { Link } from "react-router-dom";

type Status = "loading" | "degraded" | "link" | "not-found";

const content = {
  loading: { title: "読み込み中", detail: "", icon: LoaderCircle },
  degraded: { title: "画面を読み込めませんでした", detail: "接続を確認して、もう一度お試しください。", icon: AlertTriangle },
  link: { title: "リンクを確認しています", detail: "確認後、安全な画面へ移動します。", icon: LoaderCircle },
  "not-found": { title: "ページが見つかりません", detail: "URLを確認するか、概要へ戻ってください。", icon: SearchX },
} as const;

export function StatusView({ state }: { readonly state: Status }) {
  const item = content[state];
  const Icon = item.icon;
  return <main className="status-view"><Icon aria-hidden="true" className={state === "loading" || state === "link" ? "spinner" : ""} /><h1>{item.title}</h1>{item.detail && <p>{item.detail}</p>}{state === "degraded" && <button className="primary-button" onClick={() => window.location.reload()}>再読み込み</button>}{state === "not-found" && <Link className="primary-button" to="/app">概要へ戻る</Link>}</main>;
}
