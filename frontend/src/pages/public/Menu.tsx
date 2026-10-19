import { ChevronRight, Search, SearchX, X } from 'lucide-react';
import { useMemo, useState } from 'react';
import { Dialog } from '../../components/Dialog';
import { ConfirmBadge, DemoBadge, Img } from '../../components/ui';
import { publicPrice, type CategoryId, type MenuItem } from '../../data/menu';
import { useDemoLoading } from '../../components/useDemoLoading';
import { MenuHero } from '../../components/MenuHero';
import { usePageMeta, usePath } from '../../router';
import { useStore } from '../../store';

const norm = (s: string) => s.toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '');

function ItemDetail({ item, onClose }: { item: MenuItem; onClose: () => void }) {
  const { mediaAsset, categories } = useStore();
  const photo = mediaAsset(item.photo);
  const cat = categories.find((c) => c.id === item.category);
  return (
    <Dialog title={item.name} onClose={onClose}>
      <div className="item-detail">
        {photo && <Img asset={photo} eager className="item-detail__img" sizes="(min-width: 760px) 360px, 100vw" />}
        <div className="item-detail__body">
          <p className="item-detail__cat">{cat?.label}</p>
          <p>{item.description}</p>
          <dl className="item-detail__facts">
            <div>
              <dt>Prezzo</dt>
              <dd>
                {publicPrice(item).text}
                {publicPrice(item).demo && <span className="badge badge--demo price-demo">prezzo dimostrativo</span>}
              </dd>
            </div>
            <div>
              <dt>Ingredienti</dt>
              <dd>
                {item.ingredients.length ? item.ingredients.join(', ') : 'Da indicare'}
                {item.source === 'foto' && <span className="muted small"> — dedotti dalla foto</span>}
              </dd>
            </div>
            <div>
              <dt>Allergeni</dt>
              <dd>
                {item.allergens?.length ? item.allergens.join(', ') : <ConfirmBadge>Da dichiarare (Reg. UE 1169/2011)</ConfirmBadge>}
              </dd>
            </div>
          </dl>
          <p className="small muted">
            Hai un'allergia o un'intolleranza? Chiedi sempre allo staff prima di ordinare.
          </p>
          {item.source === 'demo' && <DemoBadge>Voce segnaposto</DemoBadge>}
          {item.source === 'foto' && <DemoBadge>Nome e ricetta da confermare</DemoBadge>}
        </div>
      </div>
    </Dialog>
  );
}

export function Menu() {
  usePageMeta('Menu');
  const path = usePath();
  const { menu, mediaAsset, categories } = useStore();
  const initialCat = new URLSearchParams(path.split('?')[1] ?? '').get('cat') as CategoryId | null;
  const [cat, setCat] = useState<CategoryId | 'tutti'>(categories.some((c) => c.id === initialCat) ? initialCat! : 'tutti');
  const [query, setQuery] = useState('');
  const [open, setOpen] = useState<MenuItem | null>(null);
  const loading = useDemoLoading();

  const visible = useMemo(() => menu.filter((m) => m.visible), [menu]);
  const results = useMemo(() => {
    const q = norm(query.trim());
    return visible.filter(
      (m) =>
        (cat === 'tutti' || m.category === cat) &&
        (!q || norm(`${m.name} ${m.description} ${m.ingredients.join(' ')}`).includes(q)),
    );
  }, [visible, cat, query]);

  const groups = categories
    .map((c) => ({ ...c, items: results.filter((r) => r.category === c.id) }))
    .filter((g) => g.items.length);

  return (
    <>
      <MenuHero />

      <section className="container menu" aria-label="Voci del menu">
        <div className="menu__tools">
          <div className="menu__search">
            <label htmlFor="menu-q" className="sr-only">Cerca nel menu</label>
            <Search size={20} aria-hidden="true" />
            <input
              id="menu-q"
              className="input"
              type="search"
              placeholder="Cerca: lamponi, tagliere, cappuccino…"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
            />
            {query && (
              <button type="button" className="icon-btn" aria-label="Cancella la ricerca" onClick={() => setQuery('')}>
                <X aria-hidden="true" size={18} />
              </button>
            )}
          </div>
          <div className="chips" role="group" aria-label="Filtra per categoria">
            <button type="button" className="chip" aria-pressed={cat === 'tutti'} onClick={() => setCat('tutti')}>
              Tutto<span className="count">{visible.length}</span>
            </button>
            {categories.map((c) => (
              <button key={c.id} type="button" className="chip" aria-pressed={cat === c.id} onClick={() => setCat(c.id)}>
                {c.label}
                <span className="count">{visible.filter((v) => v.category === c.id).length}</span>
              </button>
            ))}
          </div>
          <p className="sr-only" aria-live="polite">{results.length} voci trovate</p>
        </div>

        {loading ? (
          <div className="skeleton-list" aria-busy="true" aria-label="Caricamento del menu">
            {Array.from({ length: 5 }, (_, i) => (
              <div key={i} className="skeleton-row"><span className="skeleton skeleton--img" /><span className="skeleton skeleton--line" /><span className="skeleton skeleton--line skeleton--short" /></div>
            ))}
          </div>
        ) : groups.length === 0 ? (
          <div className="empty">
            <SearchX size={40} aria-hidden="true" />
            <h2>Niente da queste parti.</h2>
            <p>Nessuna voce per «{query}»{cat !== 'tutti' ? ` in ${categories.find((c) => c.id === cat)?.label}` : ''}. Prova un'altra parola o guarda tutto il menu.</p>
            <button type="button" className="btn btn--gold" onClick={() => { setQuery(''); setCat('tutti'); }}>
              Mostra tutto il menu
            </button>
          </div>
        ) : (
          groups.map((g) => (
            <section key={g.id} className="menu__group" aria-labelledby={`cat-${g.id}`}>
              <header className="menu__group-head">
                <h2 id={`cat-${g.id}`}>{g.label}</h2>
                <p className="muted">{g.hint}</p>
              </header>
              <ul className="menu__list">
                {g.items.map((item) => {
                  const p = mediaAsset(item.photo);
                  return (
                    <li key={item.id}>
                      <button type="button" className={`menu-item${p ? '' : ' menu-item--text'}`} onClick={() => setOpen(item)}>
                        {p && <Img asset={p} decorative className="menu-item__img" sizes="104px" />}
                        <span className="menu-item__body">
                          <span className="menu-item__name">{item.name}</span>
                          <span className="menu-item__desc">{item.description}</span>
                          <span className="menu-item__meta">
                            <span className="menu-item__price">{publicPrice(item).text}</span>
                            {publicPrice(item).demo && <span className="badge badge--demo">demo</span>}
                            {item.source === 'demo' && <span className="badge badge--demo">Segnaposto</span>}
                          </span>
                        </span>
                        <ChevronRight className="menu-item__chev" aria-hidden="true" />
                      </button>
                    </li>
                  );
                })}
              </ul>
            </section>
          ))
        )}
      </section>
      {open && <ItemDetail item={open} onClose={() => setOpen(null)} />}
    </>
  );
}
