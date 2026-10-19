import { ExternalLink, MapPin } from 'lucide-react';
import { useStore } from '../store';

/**
 * Mappa Google Maps incorporata (iframe «embed», senza chiave API).
 * Con `mapMode: 'consenso'` la mappa si carica solo dopo il clic del visitatore:
 * prima del clic nessuna richiesta parte verso Google (niente cookie di terze parti).
 */
export function MapEmbed() {
  const { settings, mapConsent, dispatch } = useStore();
  const show = settings.mapMode === 'automatico' || mapConsent;
  const src = `https://maps.google.com/maps?q=${encodeURIComponent(settings.mapQuery)}&z=16&hl=it&output=embed`;

  return (
    <div className="map">
      {show ? (
        <iframe
          className="map__frame"
          src={src}
          title={`Mappa: ${settings.mapQuery}`}
          loading="lazy"
          referrerPolicy="no-referrer-when-downgrade"
          allowFullScreen
        />
      ) : (
        <div className="map__consent">
          <MapPin size={40} aria-hidden="true" />
          <p className="map__title">Mappa di Google Maps</p>
          <p className="small">
            Caricando la mappa, Google riceve il tuo indirizzo IP e può usare cookie propri. Se preferisci, apri solo le
            indicazioni in una nuova scheda.
          </p>
          <div className="map__actions">
            <button type="button" className="btn btn--gold" onClick={() => dispatch({ type: 'map/consent' })}>
              Mostra la mappa
            </button>
            <a className="btn btn--ghost" href={settings.mapsUrl} target="_blank" rel="noopener noreferrer">
              <ExternalLink size={18} aria-hidden="true" /> Apri in Google Maps
            </a>
          </div>
        </div>
      )}
      {!settings.addressConfirmed && (
        <p className="map__note small">La posizione segue la ricerca «{settings.mapQuery}»: da sostituire con l'indirizzo esatto.</p>
      )}
    </div>
  );
}
