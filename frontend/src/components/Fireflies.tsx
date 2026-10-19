// Piccole luci che pulsano e fluttuano, ispirate all'addome fucsia della lucciola del logo.
// Solo CSS (transform/opacity), pochi elementi, nascoste con prefers-reduced-motion.
const SPOTS = [
  { x: 8, y: 22, s: 6, d: 0 }, { x: 22, y: 64, s: 4, d: 1.4 }, { x: 35, y: 18, s: 5, d: 2.6 },
  { x: 44, y: 78, s: 3, d: 0.8 }, { x: 15, y: 86, s: 5, d: 3.3 }, { x: 52, y: 40, s: 4, d: 1.9 },
  { x: 28, y: 42, s: 3, d: 4.1 }, { x: 6, y: 55, s: 4, d: 2.2 },
];

export function Fireflies({ className = '' }: { className?: string }) {
  return (
    <div className={`fireflies ${className}`} aria-hidden="true">
      {SPOTS.map((p, i) => (
        <span
          key={i}
          className={`firefly${i % 3 === 0 ? ' firefly--gold' : ''}`}
          style={{ left: `${p.x}%`, top: `${p.y}%`, width: p.s, height: p.s, animationDelay: `${p.d}s, ${p.d / 2}s` }}
        />
      ))}
    </div>
  );
}
