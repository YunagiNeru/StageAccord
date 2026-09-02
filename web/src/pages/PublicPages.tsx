import { ArrowRight, ExternalLink, ShieldCheck } from "lucide-react";
import { Link, useParams } from "react-router-dom";
import { Badge, ScenarioBoundary } from "../components/ProductUi";

export function CreatorProfilePage() {
  const { slug } = useParams();
  return <main className="public-profile"><nav><span className="brand-lockup"><span className="brand-mark">SA</span><span>StageAccord</span></span><Link to="/login">ログイン</Link></nav><ScenarioBoundary emptyTitle="プロフィールは公開されていません"><header><Badge tone="success">受付中</Badge><h1>Northline Studio</h1><p>映像とデジタルプロダクトの制作。要件、確認、納品を一つの進行表で共有します。</p><span className="record-id">/{slug}</span></header><section><h2>サービス</h2><article className="public-service"><div><h3>映像パッケージ</h3><p>構成から最終書き出しまで</p></div><Link className="primary-button" to="/services/video-package">詳細<ArrowRight size={16} /></Link></article></section></ScenarioBoundary></main>;
}

export function ServiceDetailPage() {
  return <main className="public-profile"><nav><Link className="brand-lockup" to="/creators/northline"><span className="brand-mark">SA</span><span>Northline Studio</span></Link><Link to="/login">ログイン</Link></nav><ScenarioBoundary emptyTitle="サービスは公開されていません"><header><Badge tone="success">受付中</Badge><h1>映像パッケージ</h1><p>構成、編集、確認、最終書き出しを段階ごとに共有します。</p></header><div className="service-facts"><section><h2>進行</h2><ol><li>要件確認</li><li>構成</li><li>初稿確認</li><li>仕上げ</li><li>納品</li></ol></section><aside><dl><div><dt>修正</dt><dd>2ラウンド</dd></div><div><dt>形式</dt><dd>MP4 / WebM</dd></div><div><dt>外部リンク</dt><dd><ExternalLink size={15} />明示操作で遷移</dd></div></dl><Link className="primary-button" to="/services/video-package/request">依頼する<ArrowRight size={16} /></Link></aside></div></ScenarioBoundary></main>;
}

export function ClientPortalPage() {
  const { projectAccessId } = useParams();
  return <main className="client-portal"><nav><span className="brand-lockup"><span className="brand-mark">SA</span><span>StageAccord</span></span><Badge tone="info">依頼者ポータル</Badge></nav><ScenarioBoundary><header><span className="record-id">{projectAccessId}</span><h1>ブランドサイト制作</h1><p>デザイン確認 · 9月5日まで</p></header><section className="client-next"><ShieldCheck /><div><h2>確認対象 v4</h2><p>差し替え後の最新版です。以前の承認は引き継がれていません。</p></div><button className="primary-button">確認する</button></section><ol className="compact-timeline"><li><strong>初稿 v4 を共有</strong><time>今日 11:20</time></li><li><strong>コメントを送信</strong><time>昨日 16:05</time></li><li><strong>構成を承認</strong><time>8月29日 14:12</time></li></ol></ScenarioBoundary></main>;
}
