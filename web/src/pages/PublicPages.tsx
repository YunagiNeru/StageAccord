import { useCallback } from "react";
import { ArrowRight, ShieldCheck } from "lucide-react";
import { Link, useParams } from "react-router-dom";
import { useApiEnvironment, useApiResource } from "../api/ApiContext";
import { Badge, ResourceBoundary } from "../components/ProductUi";

export function CreatorProfilePage() {
  const { slug = "" } = useParams();
  const { client } = useApiEnvironment();
  const loader = useCallback(() => client.getPublicCreator(slug), [client, slug]);
  const resource = useApiResource(loader, [loader]);
  return <main className="public-profile"><PublicNav /><ResourceBoundary resource={resource}>{(profile) => <><header><Badge tone={profile.intakeStatus === "open" ? "success" : "neutral"}>{profile.intakeStatus === "open" ? "受付中" : "受付停止中"}</Badge><h1>{profile.displayName ?? slug}</h1>{profile.bio && <p>{profile.bio}</p>}<span className="record-id">/{slug}</span></header>{profile.categories && profile.categories.length > 0 && <section><h2>分野</h2><p>{profile.categories.join(" · ")}</p></section>}</>}</ResourceBoundary></main>;
}

export function ServiceDetailPage() {
  const { slug = "" } = useParams();
  const { client } = useApiEnvironment();
  const loader = useCallback(() => client.getPublicService(slug), [client, slug]);
  const resource = useApiResource(loader, [loader]);
  return <main className="public-profile"><PublicNav /><ResourceBoundary resource={resource}>{(service) => <><header><Badge tone="success">受付中</Badge><h1>{service.content.title ?? service.slug}</h1>{(service.content.summary || service.content.description) && <p>{service.content.summary ?? service.content.description}</p>}</header><div className="service-facts"><section><h2>納品物</h2>{service.content.deliverables?.length ? <ol>{service.content.deliverables.map((item) => <li key={item}>{item}</li>)}</ol> : <p>公開情報に納品物の記載はありません。</p>}</section><aside>{service.content.revisionPolicy && <dl><div><dt>修正条件</dt><dd>{service.content.revisionPolicy}</dd></div></dl>}<Link className="primary-button" to={`/services/${service.slug}/request`}>依頼する<ArrowRight size={16} /></Link></aside></div></>}</ResourceBoundary></main>;
}

export function ClientPortalPage() {
  const { projectAccessId = "" } = useParams();
  const { client } = useApiEnvironment();
  const loader = useCallback(() => client.getClientProject(projectAccessId), [client, projectAccessId]);
  const resource = useApiResource(loader, [loader]);
  return <main className="client-portal"><nav><span className="brand-lockup"><span className="brand-mark">SA</span><span>StageAccord</span></span><Badge tone="info">依頼者ポータル</Badge></nav><ResourceBoundary resource={resource}>{(project) => <><header><span className="record-id">{projectAccessId}</span><h1>プロジェクト</h1><p>状態: {project.status}</p></header><section className="client-next"><ShieldCheck /><div><h2>現在の工程</h2><p>待機先: {project.waitingOn}</p></div><Badge tone={project.status === "active" ? "info" : "neutral"}>v{project.version}</Badge></section></>}</ResourceBoundary></main>;
}

function PublicNav() { return <nav><Link className="brand-lockup" to="/"><span className="brand-mark">SA</span><span>StageAccord</span></Link><Link to="/login">ログイン</Link></nav>; }
