import { ArrowLeft, ArrowRight, CalendarDays, CircleAlert, Clock3, Info, Minus, Pencil, Plus, ShieldCheck, Users } from 'lucide-react';
import { useEffect, useMemo, useRef, useState, type FormEvent } from 'react';
import { capitalize, formatEventDate } from '../data/events';
import { useStore } from '../store';
import { Dialog } from './Dialog';
import { ConfirmBadge } from './ui';

// Flusso dimostrativo in 6 passi. Nessun dato lascia il browser: l'ultimo passo lo dichiara esplicitamente.
type Values = { date: string; time: string; guests: number; name: string; contact: string; notes: string; privacy: boolean };
type Errors = Partial<Record<keyof Values, string>>;

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

const SLOT_GROUPS = [
  { label: 'Colazione', slots: ['07:30', '08:00', '08:30', '09:00', '09:30', '10:00', '10:30'] },
  { label: 'Pomeriggio', slots: ['15:00', '16:00', '17:00'] },
  { label: 'Aperitivo', slots: ['18:00', '18:30', '19:00', '19:30', '20:00', '20:30'] },
  { label: 'Sera', slots: ['21:00', '21:30', '22:00'] },
];

export function BookingDialog() {
  const { booking, closeBooking, events, settings } = useStore();
  const event = events.find((e) => e.id === booking.eventId);
  const [step, setStep] = useState(event?.date ? 1 : 0);
  const [values, setValues] = useState<Values>({
    date: event?.date ?? '', time: event?.time ?? '', guests: 2, name: '', contact: '', notes: '', privacy: false,
  });
  const [errors, setErrors] = useState<Errors>({});
  const [done, setDone] = useState(false);
  const bodyRef = useRef<HTMLDivElement>(null);

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
  }, [step, done]);

  const validate = (s: number, v: Values): Errors => {
    const e: Errors = {};
    if (s === 0) {
      if (!v.date) e.date = 'Scegli il giorno in cui vorresti venire.';
      else if (v.date < todayIso()) e.date = 'La data è già passata: scegline una da oggi in poi.';
    }
    if (s === 1 && !v.time) e.time = "Scegli un orario: è indicativo, il locale te lo conferma.";
    if (s === 2 && (v.guests < 1 || v.guests > 20)) e.guests = 'Indica da 1 a 20 persone. Per gruppi più grandi scrivilo nelle note.';
    if (s === 3) {
      if (v.name.trim().length < 2) e.name = 'Scrivi un nome per la prenotazione (almeno 2 lettere).';
      const c = v.contact.trim();
      if (!c) e.contact = 'Lascia un telefono o una email: serve al locale per risponderti.';
      else if (!/^\+?[\d\s.-]{8,16}$/.test(c) && !/^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(c)) e.contact = 'Controlla il recapito: es. 333 123 4567 oppure nome@email.it.';
      if (!v.privacy) e.privacy = "Per inviare la richiesta devi dichiarare di aver letto l'informativa privacy.";
    }
    return e;
  };

  const set = <K extends keyof Values>(key: K, val: Values[K]) => {
    const next = { ...values, [key]: val };
    setValues(next);
    if (errors[key]) setErrors((prev) => ({ ...prev, [key]: validate(step, next)[key] }));
  };

  const next = (ev?: FormEvent) => {
    ev?.preventDefault();
    const e = validate(step, values);
    setErrors(e);
    const first = Object.keys(e)[0];
    if (first) {
      requestAnimationFrame(() => document.getElementById(`bk-${first}`)?.focus());
      return;
    }
    if (step === 4) setDone(true);
    else setStep((s) => s + 1);
  };

  const err = (key: keyof Values) =>
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

  if (!settings.bookingEnabled) {
    return (
      <Dialog title="Richiedi un tavolo" onClose={closeBooking}>
        <p>Le richieste online sono disattivate dal locale. Scrivi su Instagram per informazioni.</p>
      </Dialog>
    );
  }

  const when = values.date ? formatEventDate({ date: values.date, time: values.time || null }) : '—';

  return (
    <Dialog title={done ? 'Richiesta simulata' : 'Richiedi un tavolo'} onClose={closeBooking}>
      <div ref={bodyRef}>
        {done ? (
          <div className="booking-done" role="status">
            <div className="booking-done__icon"><Clock3 aria-hidden="true" /></div>
            <h3 tabIndex={-1} data-step-title>Nessuna prenotazione è stata effettuata.</h3>
            <p>
              Questa è una <strong>simulazione</strong>: nella versione reale la richiesta arriverebbe al locale, che ti
              risponde al recapito indicato. <strong>Il tavolo è riservato solo dopo la risposta del locale.</strong>
            </p>
            <p className="demo-note"><Info size={18} aria-hidden="true" /> Prototipo: nessun dato è stato inviato o salvato.</p>
            <dl className="booking-recap">
              <div><dt>Quando</dt><dd>{when}</dd></div>
              <div><dt>Persone</dt><dd>{values.guests}</dd></div>
              <div><dt>Nome</dt><dd>{values.name}</dd></div>
            </dl>
            <div className="booking-actions">
              <button type="button" className="btn btn--gold" onClick={() => { setDone(false); setStep(4); }}>Torna al riepilogo</button>
              <button type="button" className="btn btn--quiet" onClick={closeBooking}>Chiudi</button>
            </div>
          </div>
        ) : (
          <form className="booking-form" noValidate onSubmit={next}>
            <ol className="stepper-bar" aria-label="Passaggi della richiesta">
              {STEPS.map((s, i) => (
                <li key={s} className={i < step ? 'is-done' : i === step ? 'is-current' : undefined} aria-current={i === step ? 'step' : undefined}>
                  <span className="stepper-bar__dot" aria-hidden="true" />
                  <span className="stepper-bar__label">{s}</span>
                </li>
              ))}
            </ol>
            <p className="small muted stepper-bar__count">Passo {step + 1} di {STEPS.length} · nessuna prenotazione viene effettuata</p>
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
                  <input id="bk-date" className="input input--date" type="date" min={todayIso()} value={values.date}
                    onChange={(e) => set('date', e.target.value)} {...aria('date')} />
                  {closedDay && (
                    <p className="hint warn"><Info size={16} aria-hidden="true" /> Di {closedDay.toLowerCase()} il locale risulta chiuso (orari da confermare). Puoi comunque inviare: ti risponderanno.</p>
                  )}
                </fieldset>
              )}

              {step === 1 && (
                <fieldset className="field">
                  <legend className="booking-step__title" tabIndex={-1} data-step-title><Clock3 aria-hidden="true" /> A che ora?</legend>
                  {err('time')}
                  {SLOT_GROUPS.map((g) => (
                    <div key={g.label} className="slot-group">
                      <p className="slot-group__label">{g.label}</p>
                      <div className="slot-chips" role="radiogroup" aria-label={g.label}>
                        {g.slots.map((t, i) => (
                          <button key={t} id={i === 0 && g.label === 'Colazione' ? 'bk-time' : undefined} type="button" role="radio"
                            aria-checked={values.time === t} className="slot-chip" onClick={() => set('time', t)}>{t}</button>
                        ))}
                      </div>
                    </div>
                  ))}
                  <p className="hint">Orario indicativo: il locale lo conferma nella risposta.</p>
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
                    <button type="button" className="icon-btn" aria-label="Una persona in più" onClick={() => set('guests', Math.min(20, values.guests + 1))} disabled={values.guests >= 20}>
                      <Plus aria-hidden="true" />
                    </button>
                  </div>
                  <div className="slot-chips">
                    {[2, 4, 6, 8, 12].map((n) => (
                      <button key={n} type="button" className="slot-chip" aria-pressed={values.guests === n} onClick={() => set('guests', n)}>{n} persone</button>
                    ))}
                  </div>
                  <p className="hint" id="bk-guests-hint">Oltre 12 persone il locale potrebbe proporti una sistemazione dedicata.</p>
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
                      <label htmlFor="bk-contact">Telefono o email</label>
                      <input id="bk-contact" className="input" autoComplete="tel" value={values.contact} onChange={(e) => set('contact', e.target.value)} {...aria('contact', true)} />
                      {err('contact')}
                      <p className="hint" id="bk-contact-hint">Lo usa solo il locale per risponderti su questa richiesta.</p>
                    </div>
                  </div>
                  <div className="field">
                    <label htmlFor="bk-notes">Note <span className="muted">(facoltativo)</span></label>
                    <textarea id="bk-notes" className="textarea" maxLength={300} value={values.notes} placeholder="Es. tavolo fuori, compleanno, seggiolone" onChange={(e) => set('notes', e.target.value)} />
                    <p className="hint">Non indicare dati sulla salute: per allergie chiedi direttamente allo staff.</p>
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
                  <dl className="booking-recap booking-recap--edit">
                    {[
                      ['Quando', when, 0],
                      ['Persone', String(values.guests), 2],
                      ['Nome', values.name, 3],
                      ['Recapito', values.contact, 3],
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
                  <p className="demo-note"><ShieldCheck size={18} aria-hidden="true" /> Inviando, in questo prototipo non parte nessun dato e non viene prenotato nessun tavolo.</p>
                </div>
              )}
            </div>

            <div className="booking-nav">
              {step > 0 && (
                <button type="button" className="btn btn--quiet" onClick={() => { setErrors({}); setStep((s) => s - 1); }}>
                  <ArrowLeft size={18} aria-hidden="true" /> Indietro
                </button>
              )}
              <button type="submit" className="btn btn--primary booking-nav__next">
                {step === 4 ? 'Invia la richiesta (simulata)' : 'Avanti'} <ArrowRight size={20} aria-hidden="true" />
              </button>
            </div>
          </form>
        )}
      </div>
    </Dialog>
  );
}
