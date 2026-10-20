import { ArrowLeft, ArrowRight, CalendarDays, CircleAlert, Clock3, Info, Minus, Pencil, Plus, ShieldCheck, Users } from 'lucide-react';
import { useEffect, useMemo, useRef, useState, type FormEvent } from 'react';
import { ApiError } from '../api/client';
import { bookingOptions, hhmm, newIdempotencyKey, sendRequest, type BookingOptions, type Receipt } from '../api/reservations';
import { capitalize, formatEventDate } from '../data/events';
import { useStore } from '../store';
import { Dialog } from './Dialog';
import { ConfirmBadge } from './ui';

// Richiesta tavolo in 5 passi + esito. La richiesta arriva al locale «in attesa»: il tavolo è riservato solo
// quando il locale la conferma, e l'esito lo dice chiaramente.
type Values = {
  date: string; time: string; guests: number; name: string; phone: string; email: string; notes: string; privacy: boolean;
  website: string;
};
type Errors = Partial<Record<keyof Values | 'form', string>>;

const STEPS = ['Giorno', 'Orario', 'Persone', 'I tuoi dati', 'Riepilogo'] as const;

const isoOf = (d: Date) => {
  const x = new Date(d);
  x.setMinutes(x.getMinutes() - x.getTimezoneOffset());
  return x.toISOString().slice(0, 10);
};
const todayIso = () => isoOf(new Date());
const dayFmt = new Intl.DateTimeFormat('it-IT', { weekday: 'short' });
const numFmt = new Intl.DateTimeFormat('it-IT', { day: 'numeric' });
const monFmt = new Intl.DateTimeFormat('it-IT', { month: 'short' });
const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/;

const SLOT_REASON: Record<string, string> = { FULL: 'completo', TOO_EARLY: 'troppo vicino', DAY_CLOSED: 'chiuso', EVENT: 'evento' };

// errori del backend → passo del modulo in cui si correggono
const STEP_OF_FIELD: Record<string, number> = { date: 0, time: 1, guests: 2, name: 3, phone: 3, email: 3, notes: 3, privacyAccepted: 3 };
const STEP_OF_CODE: Record<string, [number, keyof Values]> = {
  TOO_FAR: [0, 'date'], DAY_CLOSED: [1, 'time'], SLOT_NOT_AVAILABLE: [1, 'time'], TOO_EARLY: [1, 'time'],
  FULLY_BOOKED: [1, 'time'], EVENT_BLOCKS_SLOT: [1, 'time'], PARTY_TOO_LARGE: [2, 'guests'],
};

export function BookingDialog() {
  const { booking, closeBooking, events, settings } = useStore();
  const event = events.find((e) => e.id === booking.eventId);
  const [step, setStep] = useState(event?.date ? 1 : 0);
  const [values, setValues] = useState<Values>({
    date: event?.date ?? '', time: '', guests: 2, name: '', phone: '', email: '', notes: '', privacy: false, website: '',
  });
  const [errors, setErrors] = useState<Errors>({});
  const [receipt, setReceipt] = useState<Receipt | null>(null);
  const [sending, setSending] = useState(false);
  const [general, setGeneral] = useState<BookingOptions | null>(null);
  const [options, setOptions] = useState<{ date: string; data: BookingOptions | null; error: string | null } | null>(null);
  const [reload, setReload] = useState(0);
  // una chiave per contenuto: ripetere lo stesso invio (doppio clic, rete instabile) non crea due richieste
  const keyRef = useRef(newIdempotencyKey());
  const bodyRef = useRef<HTMLDivElement>(null);

  // regole generali (richieste aperte, persone massime, giorni prenotabili)
  useEffect(() => {
    let current = true;
    bookingOptions().then((o) => current && setGeneral(o)).catch(() => current && setGeneral(null));
    return () => { current = false; };
  }, []);

  // orari del giorno scelto, con la disponibilità per il numero di persone indicato
  useEffect(() => {
    if (step !== 1 || !values.date) return;
    let current = true;
    setOptions((o) => (o?.date === values.date ? o : { date: values.date, data: null, error: null }));
    bookingOptions(values.date, values.guests)
      .then((data) => current && setOptions({ date: values.date, data, error: null }))
      .catch((e) => current && setOptions({ date: values.date, data: null, error: e instanceof ApiError ? e.message : 'Orari non caricati.' }));
    return () => { current = false; };
  }, [step, values.date, values.guests, reload]);

  const maxParty = general?.maxPartySize || 12;

  const days = useMemo(() => Array.from({ length: 14 }, (_, i) => {
    const d = new Date();
    d.setDate(d.getDate() + i);
    const idx = (d.getDay() + 6) % 7;
    return { iso: isoOf(d), d, closed: !settings.hours[idx]?.open, today: i === 0 };
  }), [settings.hours]);

  const closedDay = useMemo(() => {
    if (!values.date) return null;
    const idx = (new Date(`${values.date}T12:00:00`).getDay() + 6) % 7;
    const h = settings.hours[idx];
    return h && !h.open ? h.day : null;
  }, [values.date, settings.hours]);

  // a ogni cambio di passo il focus va al titolo del passo (annunciato dagli screen reader)
  useEffect(() => {
    bodyRef.current?.querySelector<HTMLElement>('[data-step-title]')?.focus();
  }, [step, receipt]);

  const validate = (s: number, v: Values): Errors => {
    const e: Errors = {};
    if (s === 0) {
      if (!v.date) e.date = 'Scegli il giorno in cui vorresti venire.';
      else if (v.date < todayIso()) e.date = 'La data è già passata: scegline una da oggi in poi.';
      else if (general?.lastDate && v.date > general.lastDate) e.date = `Si può chiedere un tavolo al massimo ${general.maxAdvanceDays} giorni prima.`;
    }
    if (s === 1 && !v.time) e.time = 'Scegli uno degli orari disponibili.';
    if (s === 2 && (v.guests < 1 || v.guests > maxParty)) e.guests = `Indica da 1 a ${maxParty} persone. Per gruppi più grandi chiamaci.`;
    if (s === 3) {
      if (v.name.trim().length < 2) e.name = 'Scrivi un nome per la prenotazione (almeno 2 lettere).';
      const phone = v.phone.trim();
      if (!phone) e.phone = 'Il telefono è obbligatorio: serve al locale per risponderti.';
      else if (!/^\+?[\d\s.()/-]{6,20}$/.test(phone) || phone.replace(/\D/g, '').length < 6) e.phone = 'Controlla il numero: es. 333 123 4567.';
      if (v.email.trim() && !EMAIL.test(v.email.trim())) e.email = "Controlla l'email, es. nome@email.it (oppure lasciala vuota).";
      if (!v.privacy) e.privacy = "Per inviare la richiesta devi dichiarare di aver letto l'informativa privacy.";
    }
    return e;
  };

  const set = <K extends keyof Values>(key: K, val: Values[K]) => {
    const next = { ...values, [key]: val };
    if (key === 'date' && val !== values.date) next.time = ''; // gli orari dipendono dal giorno
    setValues(next);
    keyRef.current = newIdempotencyKey(); // dati diversi: è un'altra richiesta
    if (errors[key] || errors.form) setErrors((prev) => ({ ...prev, form: undefined, [key]: validate(step, next)[key] }));
  };

  const focusField = (key: string) => requestAnimationFrame(() => document.getElementById(`bk-${key}`)?.focus());

  const submit = async () => {
    setSending(true);
    setErrors({});
    try {
      const r = await sendRequest({
        date: values.date, time: values.time, guests: values.guests, name: values.name.trim(), phone: values.phone.trim(),
        email: values.email.trim(), notes: values.notes.trim(), privacyAccepted: values.privacy, website: values.website,
      }, keyRef.current);
      setReceipt(r);
    } catch (e) {
      if (!(e instanceof ApiError)) {
        setErrors({ form: 'Si è verificato un errore imprevisto. Riprova tra poco.' });
        return;
      }
      const field = (e.problem as { context?: { field?: string } } | undefined)?.context?.field;
      const target = field && field in STEP_OF_FIELD ? ([STEP_OF_FIELD[field], field === 'privacyAccepted' ? 'privacy' : field] as const)
        : STEP_OF_CODE[e.code];
      if (target) {
        const [s, key] = target;
        if (s === 1) setReload((n) => n + 1); // gli orari sono cambiati: si ricaricano
        setStep(s);
        setErrors({ [key]: e.message });
        focusField(key);
        return;
      }
      setErrors({ form: e.status === 0 ? 'Il server non risponde: controlla la connessione e riprova.' : e.message });
    } finally {
      setSending(false);
    }
  };

  const next = (ev?: FormEvent) => {
    ev?.preventDefault();
    if (sending) return;
    const e = validate(step, values);
    setErrors(e);
    const first = Object.keys(e)[0];
    if (first) {
      focusField(first);
      return;
    }
    if (step === 4) void submit();
    else setStep((s) => s + 1);
  };

  const err = (key: keyof Errors) =>
    errors[key] ? (
      <p className="error" id={`bk-${key}-err`} role="alert">
        <CircleAlert size={16} aria-hidden="true" />
        {errors[key]}
      </p>
    ) : null;

  const aria = (key: keyof Values, hint = false) => ({
    'aria-invalid': errors[key] ? (true as const) : undefined,
    'aria-describedby': [errors[key] ? `bk-${key}-err` : '', hint ? `bk-${key}-hint` : ''].filter(Boolean).join(' ') || undefined,
  });

  if (general && !general.webBookingEnabled) {
    return (
      <Dialog title="Richiedi un tavolo" onClose={closeBooking}>
        <p>Al momento il locale non accetta richieste dal sito. {settings.phone ? `Chiamaci al ${settings.phone}.` : 'Scrivici su Instagram per informazioni.'}</p>
      </Dialog>
    );
  }

  const when = values.date ? formatEventDate({ date: values.date, time: values.time || null }) : '—';
  const groups = options?.date === values.date ? options.data?.groups ?? null : null;

  return (
    <Dialog title={receipt ? 'Richiesta inviata' : 'Richiedi un tavolo'} onClose={closeBooking}>
      <div ref={bodyRef}>
        {receipt ? (
          <div className="booking-done" role="status">
            <div className="booking-done__icon"><Clock3 aria-hidden="true" /></div>
            <h3 tabIndex={-1} data-step-title>Richiesta ricevuta: non è ancora una prenotazione.</h3>
            <p>
              Il locale controlla i posti e ti risponde {values.email.trim() ? 'via email o al telefono indicato' : 'al telefono indicato'}.
              {' '}<strong>Il tavolo è riservato solo dopo la conferma del locale.</strong>
            </p>
            <p className="demo-note"><Info size={18} aria-hidden="true" /> Codice della richiesta: <strong className="booking-ref">{receipt.reference}</strong></p>
            <dl className="booking-recap">
              <div><dt>Quando</dt><dd>{formatEventDate({ date: receipt.date, time: hhmm(receipt.time) })}</dd></div>
              <div><dt>Persone</dt><dd>{receipt.guests}</dd></div>
              <div><dt>Nome</dt><dd>{values.name}</dd></div>
              <div><dt>Stato</dt><dd>In attesa di conferma</dd></div>
            </dl>
            <div className="booking-actions">
              <button type="button" className="btn btn--gold" onClick={closeBooking}>Chiudi</button>
            </div>
          </div>
        ) : (
          <form className="booking-form" noValidate onSubmit={next} aria-busy={sending}>
            <ol className="stepper-bar" aria-label="Passaggi della richiesta">
              {STEPS.map((s, i) => (
                <li key={s} className={i < step ? 'is-done' : i === step ? 'is-current' : undefined} aria-current={i === step ? 'step' : undefined}>
                  <span className="stepper-bar__dot" aria-hidden="true" />
                  <span className="stepper-bar__label">{s}</span>
                </li>
              ))}
            </ol>
            <p className="small muted stepper-bar__count">Passo {step + 1} di {STEPS.length} · il locale conferma ogni richiesta</p>
            {event && <p className="booking-event">Per la serata <strong>{event.title}</strong> · {formatEventDate(event)}</p>}

            <div className="booking-step" key={step}>
              {step === 0 && (
                <fieldset className="field">
                  <legend className="booking-step__title" tabIndex={-1} data-step-title><CalendarDays aria-hidden="true" /> Quando vuoi venire?</legend>
                  {err('date')}
                  <div className="day-chips" role="radiogroup" aria-label="Prossimi 14 giorni">
                    {days.map((d) => (
                      <button key={d.iso} type="button" role="radio" aria-checked={values.date === d.iso}
                        className={`day-chip${d.closed ? ' is-closed' : ''}`} onClick={() => set('date', d.iso)}
                        aria-label={`${capitalize(new Intl.DateTimeFormat('it-IT', { weekday: 'long', day: 'numeric', month: 'long' }).format(d.d))}${d.closed ? ', chiuso secondo gli orari' : ''}`}>
                        <span className="day-chip__dow">{d.today ? 'Oggi' : capitalize(dayFmt.format(d.d))}</span>
                        <span className="day-chip__num">{numFmt.format(d.d)}</span>
                        <span className="day-chip__mon">{d.closed ? 'chiuso*' : monFmt.format(d.d)}</span>
                      </button>
                    ))}
                  </div>
                  <label className="small" htmlFor="bk-date">Oppure scegli un'altra data</label>
                  <input id="bk-date" className="input input--date" type="date" min={todayIso()} max={general?.lastDate ?? undefined} value={values.date}
                    onChange={(e) => set('date', e.target.value)} {...aria('date')} />
                  {closedDay && (
                    <p className="hint warn"><Info size={16} aria-hidden="true" /> Di {closedDay.toLowerCase()} il locale risulta chiuso: al passo successivo vedrai se ci sono orari disponibili.</p>
                  )}
                </fieldset>
              )}

              {step === 1 && (
                <fieldset className="field">
                  <legend className="booking-step__title" tabIndex={-1} data-step-title><Clock3 aria-hidden="true" /> A che ora?</legend>
                  {err('time')}
                  {options?.error && (
                    <p className="error" role="alert">
                      <CircleAlert size={16} aria-hidden="true" /> {options.error}{' '}
                      <button type="button" className="btn btn--quiet btn--sm" onClick={() => setReload((n) => n + 1)}>Riprova</button>
                    </p>
                  )}
                  {!groups && !options?.error && <p className="muted" role="status">Carico gli orari disponibili…</p>}
                  {groups && groups.length === 0 && (
                    <p className="hint warn" id="bk-time" tabIndex={-1}><Info size={16} aria-hidden="true" /> In questo giorno non ci sono orari prenotabili dal sito: scegli un altro giorno{settings.phone ? ` o chiamaci al ${settings.phone}` : ''}.</p>
                  )}
                  {groups?.map((g, gi) => (
                    <div key={g.name + gi} className="slot-group">
                      <p className="slot-group__label">{g.name}</p>
                      <div className="slot-chips" role="radiogroup" aria-label={g.name}>
                        {g.slots.map((s, i) => {
                          const t = hhmm(s.time);
                          return (
                            <button key={t} id={gi === 0 && i === 0 ? 'bk-time' : undefined} type="button" role="radio"
                              aria-checked={values.time === t} className="slot-chip" disabled={!s.available}
                              aria-label={s.available ? t : `${t}, ${SLOT_REASON[s.reason ?? ''] ?? 'non disponibile'}`}
                              onClick={() => set('time', t)}>{t}</button>
                          );
                        })}
                      </div>
                    </div>
                  ))}
                  <p className="hint">Gli orari barrati non sono disponibili per {values.guests} {values.guests === 1 ? 'persona' : 'persone'}. Il locale conferma l'orario nella risposta.</p>
                </fieldset>
              )}

              {step === 2 && (
                <fieldset className="field">
                  <legend className="booking-step__title" tabIndex={-1} data-step-title><Users aria-hidden="true" /> Quanti siete?</legend>
                  {err('guests')}
                  <div className="stepper stepper--big" role="group" aria-label="Numero di persone">
                    <button type="button" className="icon-btn" aria-label="Una persona in meno" onClick={() => set('guests', Math.max(1, values.guests - 1))} disabled={values.guests <= 1}>
                      <Minus aria-hidden="true" />
                    </button>
                    <input id="bk-guests" className="input" inputMode="numeric" aria-label="Persone" value={values.guests}
                      onChange={(e) => set('guests', Number(e.target.value.replace(/\D/g, '')) || 0)} {...aria('guests', true)} />
                    <button type="button" className="icon-btn" aria-label="Una persona in più" onClick={() => set('guests', Math.min(maxParty, values.guests + 1))} disabled={values.guests >= maxParty}>
                      <Plus aria-hidden="true" />
                    </button>
                  </div>
                  <div className="slot-chips">
                    {[2, 4, 6, 8, 12].filter((n) => n <= maxParty).map((n) => (
                      <button key={n} type="button" className="slot-chip" aria-pressed={values.guests === n} onClick={() => set('guests', n)}>{n} persone</button>
                    ))}
                  </div>
                  <p className="hint" id="bk-guests-hint">Dal sito fino a {maxParty} persone: per gruppi più grandi chiamaci.</p>
                </fieldset>
              )}

              {step === 3 && (
                <div className="booking-fields">
                  <p className="booking-step__title" tabIndex={-1} data-step-title>I tuoi dati</p>
                  <div className="booking-grid">
                    <div className="field">
                      <label htmlFor="bk-name">Nome</label>
                      <input id="bk-name" className="input" autoComplete="given-name" value={values.name} onChange={(e) => set('name', e.target.value)} {...aria('name')} />
                      {/* errori sotto il campo: le due caselle restano allineate anche con messaggi di lunghezza diversa */}
                      {err('name')}
                    </div>
                    <div className="field">
                      <label htmlFor="bk-phone">Telefono</label>
                      <input id="bk-phone" className="input" type="tel" autoComplete="tel" value={values.phone} onChange={(e) => set('phone', e.target.value)} {...aria('phone', true)} />
                      {err('phone')}
                      <p className="hint" id="bk-phone-hint">Lo usa solo il locale per risponderti su questa richiesta.</p>
                    </div>
                  </div>
                  <div className="field">
                    <label htmlFor="bk-email">Email <span className="muted">(facoltativa)</span></label>
                    <input id="bk-email" className="input" type="email" autoComplete="email" value={values.email} onChange={(e) => set('email', e.target.value)} {...aria('email')} />
                    {err('email')}
                  </div>
                  <div className="field">
                    <label htmlFor="bk-notes">Note <span className="muted">(facoltativo)</span></label>
                    <textarea id="bk-notes" className="textarea" maxLength={500} value={values.notes} placeholder="Es. tavolo fuori, compleanno, seggiolone" onChange={(e) => set('notes', e.target.value)} />
                    <p className="hint">Non indicare dati sulla salute: per allergie chiedi direttamente allo staff.</p>
                  </div>
                  {/* campo trappola per i programmi automatici: invisibile e saltato da tastiera e screen reader */}
                  <div className="hp-field" aria-hidden="true">
                    <label htmlFor="bk-website">Sito web</label>
                    <input id="bk-website" tabIndex={-1} autoComplete="off" value={values.website} onChange={(e) => set('website', e.target.value)} />
                  </div>
                  <div className="field">
                    {err('privacy')}
                    <label className="check" htmlFor="bk-privacy">
                      <input id="bk-privacy" type="checkbox" checked={values.privacy} onChange={(e) => set('privacy', e.target.checked)} {...aria('privacy')} />
                      <span>Ho letto l'informativa privacy <ConfirmBadge>testo da redigere</ConfirmBadge> e acconsento all'uso dei miei dati solo per gestire questa richiesta.</span>
                    </label>
                  </div>
                </div>
              )}

              {step === 4 && (
                <div className="booking-summary">
                  <p className="booking-step__title" tabIndex={-1} data-step-title>Controlla la richiesta</p>
                  {err('form')}
                  <dl className="booking-recap booking-recap--edit">
                    {[
                      ['Quando', when, 0],
                      ['Persone', String(values.guests), 2],
                      ['Nome', values.name, 3],
                      ['Telefono', values.phone, 3],
                      ['Email', values.email || '—', 3],
                      ['Note', values.notes || '—', 3],
                    ].map(([k, v, s]) => (
                      <div key={k as string}>
                        <dt>{k}</dt>
                        <dd>{v}</dd>
                        <button type="button" className="btn btn--quiet btn--sm" onClick={() => setStep(s as number)} aria-label={`Modifica ${String(k).toLowerCase()}`}>
                          <Pencil size={14} aria-hidden="true" /> Modifica
                        </button>
                      </div>
                    ))}
                  </dl>
                  <p className="demo-note"><ShieldCheck size={18} aria-hidden="true" /> Inviando chiedi un tavolo: non è ancora una prenotazione finché il locale non la conferma.</p>
                </div>
              )}
            </div>

            <div className="booking-nav">
              {step > 0 && (
                <button type="button" className="btn btn--quiet" disabled={sending} onClick={() => { setErrors({}); setStep((s) => s - 1); }}>
                  <ArrowLeft size={18} aria-hidden="true" /> Indietro
                </button>
              )}
              <button type="submit" className="btn btn--primary booking-nav__next" disabled={sending}>
                {step === 4 ? (sending ? 'Invio in corso…' : 'Invia la richiesta') : 'Avanti'} <ArrowRight size={20} aria-hidden="true" />
              </button>
            </div>
          </form>
        )}
      </div>
    </Dialog>
  );
}
