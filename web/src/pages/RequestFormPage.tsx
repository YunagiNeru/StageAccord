import { useState } from "react";

export function RequestFormPage() {
  const [sent, setSent] = useState(false);
  if (sent) return <main className="public-surface"><section><span className="brand-mark">SA</span><h1>依頼を受け付けました</h1><p>受付番号: <strong>REQ-DEMO-001</strong></p></section></main>;
  return <main className="public-surface"><form onSubmit={(event) => { event.preventDefault(); setSent(true); }}><span className="brand-mark">SA</span><h1>制作のご依頼</h1><label htmlFor="summary">依頼内容</label><textarea id="summary" required placeholder="制作物と希望時期" /><button className="primary-button" type="submit">依頼を送信</button></form></main>;
}
