#!/usr/bin/env node
// Controllo dalle API pubbliche dopo il reset dei dati di prova (sola lettura, nessun login).
//   node infra/scripts/reset-test-data/check-api.mjs http://localhost:8080 ~/fireflies-backups/reset-…/blocked-slots-before.txt
// Verifica: nessun evento pubblico; nei giorni prima riservati a eventi esclusivi nessun orario risulta più «EVENT";
// gli orari restano prenotabili se il giorno è aperto e rispettano le altre regole (anticipo, chiusure, capienza).
import { readFileSync } from 'node:fs';

const [base = 'http://localhost:8080', blockedFile] = process.argv.slice(2);
const get = async (path) => {
  const r = await fetch(`${base}/api/v1${path}`);
  if (!r.ok) throw new Error(`${path}: HTTP ${r.status}`);
  return r.json();
};

let problems = 0;
const events = await get('/public/events');
console.log(`Eventi pubblici: ${events.length}${events.length ? '  ← attesi 0' : ''}`);
if (events.length) problems++;

const days = blockedFile
  ? readFileSync(blockedFile, 'utf8').split('\n').map((l) => l.trim()).filter(Boolean).map((l) => l.split('|'))
  : [];
if (!days.length) console.log('Nessun giorno riservato a eventi esclusivi da ricontrollare.');

const general = await get('/public/booking-options');
if (!general.webBookingEnabled) console.log('Nota: le richieste dal sito sono disattivate nelle regole: nessun orario sarà prenotabile.');

for (const [date, from, to] of days) {
  const o = await get(`/public/booking-options?date=${date}&guests=2`);
  const slots = o.groups.flatMap((g) => g.slots.map((s) => ({ ...s, group: g.name })));
  const inRange = slots.filter((s) => s.time.slice(0, 5) >= from && s.time.slice(0, 5) <= to);
  const event = slots.filter((s) => s.reason === 'EVENT');
  const free = inRange.filter((s) => s.available);
  const reasons = [...new Set(inRange.filter((s) => !s.available).map((s) => s.reason))];
  const closed = (o.closedDates ?? []).includes(date) || (general.closedDates ?? []).includes(date);
  console.log(`${date} ${from}–${to}: ${free.length}/${inRange.length} orari prenotabili`
    + (reasons.length ? `, altri non disponibili per: ${reasons.join(', ')}` : '')
    + (closed ? ' (giorno chiuso)' : '')
    + (inRange.length === 0 ? ' (nessuna fascia prenotabile in quell\'orario)' : '')
    + (event.length ? `  ← ${event.length} orari ancora bloccati da un evento` : ''));
  if (event.length) problems++;
}
console.log(problems ? `\n${problems} problemi trovati.` : '\nControllo superato: nessun evento e nessun orario bloccato da eventi.');
process.exit(problems ? 1 : 0);
