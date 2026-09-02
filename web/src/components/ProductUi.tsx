import type { ReactNode } from "react";
import { AlertTriangle, CheckCircle2, CircleSlash2, LoaderCircle, SearchX } from "lucide-react";
import { useSearchParams } from "react-router-dom";

export type Tone = "neutral" | "info" | "success" | "warning" | "danger";

export function Badge({ children, tone = "neutral" }: { readonly children: ReactNode; readonly tone?: Tone }) {
  return <span className={`badge badge--${tone}`}>{children}</span>;
}

export function PageHeader({ title, detail, action }: {
  readonly title: string; readonly detail?: string; readonly action?: ReactNode;
}) {
  return <header className="page-heading"><div><h1>{title}</h1>{detail && <p>{detail}</p>}</div>{action}</header>;
}

export function ScenarioBoundary({ children, emptyTitle = "表示する項目はありません" }: {
  readonly children: ReactNode; readonly emptyTitle?: string;
}) {
  const [params] = useSearchParams();
  const state = params.get("state");
  if (state === "loading") return <div className="skeleton-list" aria-label="読み込み中" aria-busy="true"><i /><i /><i /></div>;
  const views = {
    empty: { icon: SearchX, title: emptyTitle, detail: "" },
    forbidden: { icon: CircleSlash2, title: "この画面を表示する権限がありません", detail: "権限が更新された場合は、再度ログインしてください。" },
    expired: { icon: AlertTriangle, title: "操作期限が切れました", detail: "画面を再読み込みして、現在の状態を確認してください。" },
    conflict: { icon: AlertTriangle, title: "ほかの操作が先に反映されました", detail: "最新状態を読み込んでから、もう一度操作してください。" },
    degraded: { icon: LoaderCircle, title: "一部の情報を取得できません", detail: "復旧するまで、この画面からの変更はできません。" },
  } as const;
  if (state && state in views) {
    const item = views[state as keyof typeof views];
    const Icon = item.icon;
    return <section className="inline-state" role={state === "degraded" ? "alert" : "status"}><Icon aria-hidden="true" /><h2>{item.title}</h2>{item.detail && <p>{item.detail}</p>}</section>;
  }
  return <>{children}</>;
}

export function SegmentedTabs({ tabs, active, onChange, label }: {
  readonly tabs: readonly string[]; readonly active: string;
  readonly onChange: (tab: string) => void; readonly label: string;
}) {
  return <div className="segmented-tabs" role="tablist" aria-label={label}>{tabs.map((tab) =>
    <button key={tab} type="button" role="tab" aria-selected={active === tab} onClick={() => onChange(tab)}>{tab}</button>)}</div>;
}

export function ActionResult({ children }: { readonly children: ReactNode }) {
  return <p className="action-result" role="status"><CheckCircle2 aria-hidden="true" size={17} />{children}</p>;
}
