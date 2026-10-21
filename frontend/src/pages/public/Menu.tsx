import { ChevronRight, Search, SearchX, X } from 'lucide-react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
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

/**
 * FIX-1, req. 2 — navigazione tra le categorie: i chip portano alla sezione (scorrimento morbido, sotto l'intestazione e
 * la barra dei chip che restano in alto) e la categoria che si sta guardando resta evidenziata mentre si scorre. Tutte le
 * voci restano nella pagina: nessuna categoria nascosta, ordine e prezzi invariati.
 */
function useCategoryNav(ids: string[], ready: boolean) {
  const barRef = useRef<HTMLElement>(null);
  const rowRef = useRef<HTMLDivElement>(null);
  const [active, setActive] = useState<string | null>(null);
  const [more, setMore] = useState({ left: false, right: false });

  /** Altezza occupata in alto: intestazione del sito + barra dei chip. */
  const offset = useCallback(() => {
    const header = parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--header-h')) || 64;
    return header + (barRef.current?.offsetHeight ?? 0) + 8;
  }, []);

  const go = useCallback((id: string | null) => {
    const target = id ? document.getElementById(`cat-${id}`)?.closest('section') : document.getElementById('menu-voci');
    if (!target) return;
    const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    window.scrollTo({ top: target.getBoundingClientRect().top + window.scrollY - offset() + 1, behavior: reduce ? 'auto' : 'smooth' });
    setActive(id);
    if (id) document.getElementById(`cat-${id}`)?.focus({ preventScroll: true });
  }, [offset]);

  // categoria attiva mentre si scorre: l'ultima sezione il cui inizio è già sotto la barra
  useEffect(() => {
    if (!ready) return;
    let frame = 0;
    const update = () => {
      frame = 0;
      const line = offset() + 24;
      let current: string | null = null;
      for (const id of ids) {
        const el = document.getElementById(`cat-${id}`)?.closest('section');
        if (el && el.getBoundingClientRect().top <= line) current = id;
      }
      setActive(current);
    };
    const onScroll = () => { if (!frame) frame = requestAnimationFrame(update); };
    update();
    window.addEventListener('scroll', onScroll, { passive: true });
    window.addEventListener('resize', onScroll);
    return () => {
      cancelAnimationFrame(frame);
      window.removeEventListener('scroll', onScroll);
      window.removeEventListener('resize', onScroll);
    };
  }, [ids, ready, offset]);

  // il chip attivo resta visibile nella riga; sfumature ai lati quando ci sono altri chip da scorrere
  useEffect(() => {
    const row = rowRef.current;
    if (!row) return;
    const chip = row.querySelector<HTMLElement>('[aria-current="true"]');
    if (chip && (chip.offsetLeft < row.scrollLeft || chip.offsetLeft + chip.offsetWidth > row.scrollLeft + row.clientWidth)) {
      row.scrollTo({ left: chip.offsetLeft - (row.clientWidth - chip.offsetWidth) / 2, behavior: 'smooth' });
    }
  }, [active]);
  useEffect(() => {
    const row = rowRef.current;
    if (!row) return;
    const check = () => setMore({ left: row.scrollLeft > 4, right: row.scrollLeft + row.clientWidth < row.scrollWidth - 4 });
    check();
    row.addEventListener('scroll', check, { passive: true });
    window.addEventListener('resize', check);
    return () => {
      row.removeEventListener('scroll', check);
      window.removeEventListener('resize', check);
    };
  }, [ids, ready]);

  return { barRef, rowRef, active, more, go };
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
      // FIX-1: le categorie non filtrano più (i chip portano alla sezione); filtra solo la ricerca
      (m) => !q || norm(`${m.name} ${m.description} ${m.ingredients.join(' ')}`).includes(q),
    );
  }, [visible, query]);

  const groups = categories
    .map((c) => ({ ...c, items: results.filter((r) => r.category === c.id) }))
    .filter((g) => g.items.length);
  const ids = useMemo(() => groups.map((g) => g.id as string), [groups.map((g) => g.id).join('|')]); // eslint-disable-line react-hooks/exhaustive-deps
  const nav = useCategoryNav(ids, !loading);

  // indirizzo con ?cat=…: si apre direttamente su quella categoria (una volta, a contenuti pronti)
  const [deepLinked, setDeepLinked] = useState(false);
  useEffect(() => {
    if (loading || deepLinked || cat === 'tutti') return;
    setDeepLinked(true);
    requestAnimationFrame(() => nav.go(cat));
  }, [loading, deepLinked, cat, nav]);

  return (
    <>
      <MenuHero />

      <section className="container menu" id="menu-voci" aria-label="Voci del menu">
        <div className="menu__search">
          <label htmlFor="menu-q" className="sr-only">Cerca nel menu</label>
          <Search size={20} aria-hidden="true" />
          <input
            id="menu-q"
            className="input"
            type="search"
            placeholder="Cerca: lamponi, tagliere, cappuccino…"
            value={query}
            onChange={(e) => { setQuery(e.target.value); setCat('tutti'); }}
          />
          {query && (
            <button type="button" className="icon-btn" aria-label="Cancella la ricerca" onClick={() => setQuery('')}>
              <X aria-hidden="true" size={18} />
            </button>
          )}
        </div>
        <p className="sr-only" aria-live="polite">{results.length} voci trovate</p>
        <nav ref={nav.barRef} className="menu__nav" aria-label="Categorie del menu">
          <div className={`menu__chips-wrap${nav.more.left ? ' more-left' : ''}${nav.more.right ? ' more-right' : ''}`}>
            <div ref={nav.rowRef} className="chips menu__chips">
              <button type="button" className="chip" aria-current={nav.active === null ? 'true' : undefined} onClick={() => nav.go(null)}>
                Tutto<span className="count">{results.length}</span>
              </button>
              {groups.map((g) => (
                <button key={g.id} type="button" className="chip" aria-current={nav.active === g.id ? 'true' : undefined} onClick={() => nav.go(g.id)}>
                  {g.label}
                  <span className="count">{g.items.length}</span>
                </button>
              ))}
            </div>
          </div>
        </nav>

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
            <p>Nessuna voce per «{query}». Prova un'altra parola o guarda tutto il menu.</p>
            <button type="button" className="btn btn--gold" onClick={() => { setQuery(''); setCat('tutti'); }}>
              Mostra tutto il menu
            </button>
          </div>
        ) : (
          groups.map((g) => (
            <section key={g.id} className="menu__group" aria-labelledby={`cat-${g.id}`}>
              <header className="menu__group-head">
                <h2 id={`cat-${g.id}`} tabIndex={-1}>{g.label}</h2>
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
