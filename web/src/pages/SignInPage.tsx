import { useState } from "react";
import { ArrowRight } from "lucide-react";
import { useNavigate } from "react-router-dom";

export function SignInPage({ mode = "login" }: { readonly mode?: "login" | "register" | "recover" }) {
  const [email, setEmail] = useState("");
  const [touched, setTouched] = useState(false);
  const navigate = useNavigate();
  const invalid = touched && !/^\S+@\S+\.\S+$/.test(email);
  const title = mode === "register" ? "アカウントを作成" : mode === "recover" ? "アクセスを回復" : "ログイン";
  return <main className="auth-layout"><section className="auth-brand"><div className="brand-lockup"><span className="brand-mark">SA</span><span>StageAccord</span></div><h1>依頼から納品まで、<br />合意を一か所に。</h1></section><section className="auth-form"><form onSubmit={(event) => { event.preventDefault(); setTouched(true); if (/^\S+@\S+\.\S+$/.test(email)) navigate("/app"); }} noValidate><h2>{title}</h2><label htmlFor="email">メールアドレス</label><div className={invalid ? "field field--error" : "field"}><input id="email" type="email" value={email} onBlur={() => setTouched(true)} onChange={(event) => setEmail(event.target.value)} aria-invalid={invalid} aria-describedby="email-help" placeholder="name@example.com" /><span aria-hidden="true">{invalid ? "!" : ""}</span></div><p id="email-help" className={invalid ? "field-help field-help--error" : "field-help"}>{invalid ? "メールアドレスの形式を確認してください。" : " "}</p><button className="primary-button" type="submit">{mode === "recover" ? "回復リンクを送信" : "メールで続ける"}<ArrowRight size={16} /></button></form></section></main>;
}
