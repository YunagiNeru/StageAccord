import { useState, type FormEvent } from "react";
import { ArrowRight } from "lucide-react";
import { useNavigate } from "react-router-dom";
import { useApiEnvironment } from "../api/ApiContext";

export function SignInPage({ mode = "login" }: { readonly mode?: "login" | "register" | "recover" }) {
  const { client } = useApiEnvironment();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [totpCode, setTotpCode] = useState("");
  const [touched, setTouched] = useState(false);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const navigate = useNavigate();
  const invalid = touched && !/^\S+@\S+\.\S+$/.test(email);
  const title = mode === "register" ? "アカウントを作成" : mode === "recover" ? "アクセスを回復" : "ログイン";
  const submit = async (event: FormEvent) => {
    event.preventDefault(); setTouched(true); setMessage("");
    if (!/^\S+@\S+\.\S+$/.test(email)) return;
    setBusy(true);
    try {
      if (mode === "login") { await client.authenticate(email, password, totpCode); navigate("/app"); }
      else if (mode === "recover") { await client.startRecovery(email); setMessage("回復手続きを開始しました"); }
      else { await client.startEmailVerification(email); setMessage("確認手続きを開始しました"); }
    } catch (error) { setMessage(error instanceof Error ? error.message : "要求を完了できませんでした。"); }
    finally { setBusy(false); }
  };
  return <main className="auth-layout"><section className="auth-brand"><div className="brand-lockup"><span className="brand-mark">SA</span><span>StageAccord</span></div><h1>依頼から納品まで、<br />合意を一か所に。</h1></section><section className="auth-form"><form onSubmit={submit} noValidate><h2>{title}</h2>
    <label htmlFor="email">メールアドレス</label><div className={invalid ? "field field--error" : "field"}><input id="email" type="email" value={email} onBlur={() => setTouched(true)} onChange={(event) => setEmail(event.target.value)} aria-invalid={invalid} aria-describedby="email-help" autoComplete="email" /><span aria-hidden="true">{invalid ? "!" : ""}</span></div><p id="email-help" className={invalid ? "field-help field-help--error" : "field-help"}>{invalid ? "メールアドレスの形式を確認してください。" : " "}</p>
    {mode === "login" && <><label htmlFor="password">パスワード</label><div className="field"><input id="password" type="password" required minLength={12} maxLength={128} value={password} onChange={(event) => setPassword(event.target.value)} autoComplete="current-password" /></div><p className="field-help"> </p><label htmlFor="totp">認証コード</label><div className="field"><input id="totp" inputMode="numeric" pattern="[0-9]{6}" minLength={6} maxLength={6} required value={totpCode} onChange={(event) => setTotpCode(event.target.value)} autoComplete="one-time-code" /></div><p className="field-help">6桁</p></>}
    {message && <p className="field-help" role="status">{message}</p>}<button className="primary-button" type="submit" disabled={busy} aria-busy={busy}>{busy ? "処理中…" : mode === "recover" ? "回復手続きを開始" : mode === "register" ? "確認を開始" : "ログイン"}<ArrowRight size={16} /></button>
  </form></section></main>;
}
