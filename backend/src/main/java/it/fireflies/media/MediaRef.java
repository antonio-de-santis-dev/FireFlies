package it.fireflies.media;

import java.util.UUID;

/**
 * Foto pronta da mostrare: {@code src} (variante media) e {@code srcset} con tutte le larghezze, così il browser
 * scarica solo la dimensione che gli serve. {@code width}/{@code height} danno le proporzioni (niente salti di layout).
 */
public record MediaRef(UUID id, String alt, int width, int height, String src, String srcset) {
}
