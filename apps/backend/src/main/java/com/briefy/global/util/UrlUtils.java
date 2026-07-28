package com.briefy.global.util;

import java.net.URI;
import java.net.URISyntaxException;

public final class UrlUtils {

  private UrlUtils() {}

  /**
   * Canonicalizes a URL for deduplication and exposure comparison.
   *
   * <ul>
   *   <li>Scheme and host are lowercased
   *   <li>Query and fragment are stripped
   *   <li>Trailing slashes on non-root paths are stripped
   *   <li>Path case is preserved (paths may be case-sensitive on some servers)
   * </ul>
   *
   * <p>Returns the original URL unchanged if parsing fails, so callers never crash on malformed
   * input.
   */
  public static String canonicalize(String url) {
    if (url == null) return null;
    try {
      URI uri = new URI(url);
      String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase() : "";
      String host = uri.getHost() != null ? uri.getHost().toLowerCase() : "";
      String path = uri.getPath() != null ? uri.getPath() : "";
      if (!path.equals("/") && path.endsWith("/")) {
        path = path.replaceAll("/+$", "");
      }
      return scheme + "://" + host + path;
    } catch (URISyntaxException e) {
      return url; // safe fallback: return original rather than mangling path casing
    }
  }
}
