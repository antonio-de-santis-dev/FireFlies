import { CircleAlert, FlaskConical } from 'lucide-react';
import type { ImgHTMLAttributes, ReactNode } from 'react';
import type { Asset } from '../data/assets';

type ImgProps = Omit<ImgHTMLAttributes<HTMLImageElement>, 'src' | 'alt' | 'width' | 'height'> & {
  asset: Asset;
  eager?: boolean;
  decorative?: boolean;
};

/** Immagine con dimensioni intrinseche dichiarate (niente CLS) e lazy loading sotto la piega. */
export function Img({ asset, eager, decorative, ...rest }: ImgProps) {
  return (
    <img
      src={asset.src}
      alt={decorative ? '' : asset.alt}
      width={asset.w}
      height={asset.h}
      loading={eager ? 'eager' : 'lazy'}
      decoding="async"
      {...rest}
    />
  );
}

export function DemoBadge({ children = 'Contenuto demo' }: { children?: ReactNode }) {
  return (
    <span className="badge badge--demo">
      <FlaskConical aria-hidden="true" />
      {children}
    </span>
  );
}

export function ConfirmBadge({ children = 'Da confermare' }: { children?: ReactNode }) {
  return (
    <span className="badge badge--confirm">
      <CircleAlert aria-hidden="true" />
      {children}
    </span>
  );
}
