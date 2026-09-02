import { useCallback, useEffect, useRef, useState } from "react";
import { Bell, BriefcaseBusiness, Command, Inbox, LayoutDashboard, Menu, Settings2, Shapes, X } from "lucide-react";
import { NavLink, Outlet, useNavigate } from "react-router-dom";
import type { SessionSnapshot } from "../domain/session";
import { useApiEnvironment, useApiResource } from "../api/ApiContext";

const navigation = [
  { to: "/app", label: "概要", icon: LayoutDashboard, end: true },
  { to: "/app/requests", label: "受付", icon: Inbox, end: false },
  { to: "/app/projects", label: "プロジェクト", icon: BriefcaseBusiness, end: false },
  { to: "/app/services", label: "サービス", icon: Shapes, end: false },
  { to: "/app/settings/notifications", label: "設定", icon: Settings2, end: false },
] as const;

export function AppShell({ session }: { readonly session: SessionSnapshot }) {
  const [menuOpen, setMenuOpen] = useState(false);
  const [paletteOpen, setPaletteOpen] = useState(false);
  const [notificationsOpen, setNotificationsOpen] = useState(false);
  const searchRef = useRef<HTMLInputElement>(null);
  const paletteRef = useRef<HTMLDialogElement>(null);
  const navigate = useNavigate();
  const { client } = useApiEnvironment();
  const notificationLoader = useCallback(() => client.listNotifications(), [client]);
  const notifications = useApiResource(notificationLoader, [notificationLoader]);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k") {
        event.preventDefault();
        setPaletteOpen(true);
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, []);

  useEffect(() => {
    if (paletteOpen) {
      paletteRef.current?.showModal();
      searchRef.current?.focus();
    } else if (paletteRef.current?.open) paletteRef.current.close();
  }, [paletteOpen]);

  const openRoute = (path: string) => {
    navigate(path);
    setPaletteOpen(false);
    setMenuOpen(false);
  };

  return (
    <div className="app-shell">
      <aside className={menuOpen ? "side-rail side-rail--open" : "side-rail"} aria-label="主ナビゲーション">
        <div className="brand-lockup"><span className="brand-mark">SA</span><span>StageAccord</span></div>
        <nav className="side-nav">
          {navigation.map(({ to, label, icon: Icon, end }) => (
            <NavLink key={to} to={to} end={end} onClick={() => setMenuOpen(false)}>
              <Icon aria-hidden="true" size={18} /><span>{label}</span>
            </NavLink>
          ))}
        </nav>
        <div className="account-summary">
          <span className="avatar" aria-hidden="true">山</span>
          <span><strong>{session.actorName}</strong><small>{session.workspaceName}</small></span>
        </div>
      </aside>
      {menuOpen && <button className="nav-backdrop" aria-label="メニューを閉じる" onClick={() => setMenuOpen(false)} />}
      <div className="workspace">
        <header className="topbar">
          <button className="icon-button mobile-only" aria-label="メニューを開く" onClick={() => setMenuOpen(true)}><Menu /></button>
          <button className="command-trigger" onClick={() => setPaletteOpen(true)}><Command size={16} /><span>移動・検索</span><kbd>⌘ K</kbd></button>
          <button className="icon-button" aria-label="通知" aria-expanded={notificationsOpen} onClick={() => setNotificationsOpen((open) => !open)}><Bell /></button>
          {notificationsOpen && <aside className="notification-popover" aria-label="通知一覧"><header><strong>通知</strong><button className="icon-button" aria-label="通知を閉じる" onClick={() => setNotificationsOpen(false)}><X /></button></header>
            {notifications.status === "loading" && <p aria-busy="true">読み込み中…</p>}
            {notifications.status === "error" && <button className="secondary-button" type="button" onClick={notifications.reload}>再読み込み</button>}
            {notifications.status === "ready" && (notifications.data.length === 0 ? <p>新しい通知はありません。</p> : <ol>{notifications.data.slice(0, 8).map((item) => <li key={item.id}><span className={`activity-dot ${item.readAt ? "activity-dot--complete" : "activity-dot--new"}`} /><div><strong>{item.templateKey}</strong><small>{item.category} · {new Intl.DateTimeFormat("ja-JP", { dateStyle: "short", timeStyle: "short" }).format(new Date(item.createdAt))}</small>{!item.readAt && <button className="row-action" type="button" onClick={async () => { await client.markNotificationRead(item.id); notifications.reload(); }}>既読にする</button>}</div></li>)}</ol>)}
          </aside>}
        </header>
        <main className="main-content"><Outlet /></main>
      </div>
      <dialog ref={paletteRef} className="command-dialog" aria-label="移動・検索" onClose={() => setPaletteOpen(false)} onClick={(event) => event.target === paletteRef.current && setPaletteOpen(false)}>
          <section className="command-palette">
            <div className="command-search"><Command aria-hidden="true" /><input ref={searchRef} aria-label="移動先を検索" placeholder="画面名を入力" onKeyDown={(event) => event.key === "Escape" && setPaletteOpen(false)} /><button className="icon-button" aria-label="閉じる" onClick={() => setPaletteOpen(false)}><X /></button></div>
            <div className="command-list">{navigation.map(({ to, label, icon: Icon }) => <button key={to} onClick={() => openRoute(to)}><Icon size={17} /><span>{label}</span></button>)}</div>
          </section>
      </dialog>
    </div>
  );
}
