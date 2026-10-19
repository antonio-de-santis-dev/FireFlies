import { CalendarPlus, Clock, Instagram, Mail, MapPin, Phone } from 'lucide-react';
import { MapEmbed } from '../../components/MapEmbed';
import { ConfirmBadge, Img } from '../../components/ui';
import { photos } from '../../data/assets';
import { usePageMeta } from '../../router';
import { useStore } from '../../store';
import { HoursList } from './Home';

export function Contacts() {
  usePageMeta('Contatti');
  const { settings, openBooking } = useStore();
  return (
    <>
      <section className="container page-head">
        <h1>Contatti</h1>
        <div className="page-head__side">
          <p className="lead">Per un tavolo, una festa o una domanda veloce. Il modo più rapido, oggi, è Instagram.</p>
        </div>
      </section>

      <section className="container contacts">
        <div className="contacts__cards">
          <article className="contact-card contact-card--main">
            <h2><MapPin aria-hidden="true" /> Dove siamo</h2>
            <p>{settings.addressLine}</p>
            {!settings.addressConfirmed && <ConfirmBadge>Indirizzo esatto da confermare</ConfirmBadge>}
            <MapEmbed />
            <a className="btn btn--gold" href={settings.mapsUrl} target="_blank" rel="noopener noreferrer">
              <MapPin size={20} aria-hidden="true" /> Indicazioni stradali
            </a>
          </article>

          <article className="contact-card">
            <h2><Instagram aria-hidden="true" /> Instagram</h2>
            <p>Novità, serate e messaggi diretti.</p>
            <a className="btn btn--ghost" href={settings.instagram} target="_blank" rel="noopener noreferrer">
              Scrivi a {settings.instagramHandle}
            </a>
          </article>

          <article className="contact-card">
            <h2><Phone aria-hidden="true" /> Telefono</h2>
            {settings.phone ? (
              <a className="btn btn--ghost" href={`tel:${settings.phone.replace(/\s/g, '')}`}>Chiama {settings.phone}</a>
            ) : (
              <p className="muted">Numero non ancora fornito dal locale.</p>
            )}
            <h2 className="contact-card__h2b"><Mail aria-hidden="true" /> Email</h2>
            {settings.email ? (
              <a href={`mailto:${settings.email}`}>{settings.email}</a>
            ) : (
              <p className="muted">Email non ancora fornita.</p>
            )}
          </article>

          <article className="contact-card">
            <h2><CalendarPlus aria-hidden="true" /> Un tavolo?</h2>
            <p>Mandaci una richiesta: il locale ti risponde per confermare.</p>
            <button type="button" className="btn btn--primary" onClick={() => openBooking()}>Richiedi un tavolo</button>
          </article>
        </div>

        <aside className="contacts__side">
          <div className="contacts__hours">
            <div className="visit__hours-head">
              <h2><Clock aria-hidden="true" /> Orari</h2>
              {!settings.hoursConfirmed && <ConfirmBadge />}
            </div>
            <HoursList compact />
          </div>
          <Img asset={photos.servizioTagliere} className="contacts__img" />
        </aside>
      </section>
    </>
  );
}
