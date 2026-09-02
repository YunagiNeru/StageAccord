import { useCallback, useState, type FormEvent } from "react";
import { useParams } from "react-router-dom";
import { useApiEnvironment, useApiResource } from "../api/ApiContext";
import type { SubmissionReceipt } from "../api/ApiClient";
import { ResourceBoundary } from "../components/ProductUi";

export function RequestFormPage() {
  const { slug = "" } = useParams();
  const { client } = useApiEnvironment();
  const loader = useCallback(() => client.getIntakeForm(slug), [client, slug]);
  const form = useApiResource(loader, [loader]);
  const [receipt, setReceipt] = useState<SubmissionReceipt | null>(null);
  const [email, setEmail] = useState("");
  const [summary, setSummary] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  if (receipt) return <main className="public-surface"><section><span className="brand-mark">SA</span><h1>依頼を受け付けました</h1><p>受付番号: <strong>{receipt.requestId}</strong></p></section></main>;
  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (form.status !== "ready") return;
    setBusy(true); setError("");
    try { setReceipt(await client.submitRequest(slug, email, summary, form.data.privacyTextVersion)); }
    catch (failure) { setError(failure instanceof Error ? failure.message : "依頼を送信できませんでした。"); }
    finally { setBusy(false); }
  };
  return <main className="public-surface"><ResourceBoundary resource={form}>{() => <form onSubmit={submit}><span className="brand-mark">SA</span><h1>制作のご依頼</h1><label htmlFor="request-email">メールアドレス</label><div className="field"><input id="request-email" type="email" required value={email} onChange={(event) => setEmail(event.target.value)} /></div><p className="field-help"> </p><label htmlFor="summary">依頼内容</label><textarea id="summary" required maxLength={20000} value={summary} onChange={(event) => setSummary(event.target.value)} />{error && <p className="field-help field-help--error" role="alert">{error}</p>}<label className="check-row"><input type="checkbox" required />プライバシー条件に同意する</label><button className="primary-button" type="submit" disabled={busy} aria-busy={busy}>{busy ? "送信中…" : "依頼を送信"}</button></form>}</ResourceBoundary></main>;
}
