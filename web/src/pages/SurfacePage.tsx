export function SurfacePage({ title }: { readonly title: string }) {
  return <section className="surface-page page-enter"><header className="page-heading"><div><h1>{title}</h1></div></header><div className="empty-state"><span aria-hidden="true">—</span><h2>表示する項目はありません</h2></div></section>;
}
