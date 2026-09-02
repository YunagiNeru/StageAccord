import { useEffect, useRef, useState } from "react";
import { Bell, BriefcaseBusiness, Command, Inbox, LayoutDashboard, Menu, Settings2, Shapes, X } from "lucide-react";
import { NavLink, Outlet, useNavigate } from "react-router-dom";
import type { SessionSnapshot } from "../domain/session";

const navigation = [
  { to: "/app", label: "概要", icon: LayoutDashboard, end: true },
  { to: "/app/requests", label: "受付", icon: Inbox, end: false },
  { to: "/app/projects/demo-project", label: "プロジェクト", icon: BriefcaseBusiness, end: false },
  { to: "/app/services", label: "サービス", icon: Shapes, end: false },
  { to: "/app/settings/notifications", label: "設定", icon: Settings2, end: false },
] as const;

export function AppShell({ session }: { readonly session: SessionSnapshot }) {
  const [menuOpen, setMenuOpen] = useState(false);
  const [paletteOpen, setPaletteOpen] = useState(false);
  const searchRef = useRef<HTMLInputElement>(null);
  const navigate = useNavigate();

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
    if (paletteOpen) searchRef.current?.focus();
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
          <button className="icon-button" aria-label="通知"><Bell /></button>
        </header>
        <main className="main-content"><Outlet /></main>
      </div>
      {paletteOpen && (
        <div className="dialog-layer" role="presentation" onMouseDown={(event) => event.target === event.currentTarget && setPaletteOpen(false)}>
          <section className="command-palette" role="dialog" aria-modal="true" aria-label="移動・検索">
            <div className="command-search"><Command aria-hidden="true" /><input ref={searchRef} aria-label="移動先を検索" placeholder="画面名を入力" onKeyDown={(event) => event.key === "Escape" && setPaletteOpen(false)} /><button className="icon-button" aria-label="閉じる" onClick={() => setPaletteOpen(false)}><X /></button></div>
            <div className="command-list">{navigation.map(({ to, label, icon: Icon }) => <button key={to} onClick={() => openRoute(to)}><Icon size={17} /><span>{label}</span></button>)}</div>
          </section>
        </div>
      )}
    </div>
  );
}
