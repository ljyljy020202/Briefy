package com.briefy.global.email;

import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

public final class MarkdownToHtmlConverter {

  private static final Parser PARSER = Parser.builder().build();
  private static final HtmlRenderer RENDERER = HtmlRenderer.builder().build();

  private MarkdownToHtmlConverter() {}

  public static String convert(String markdown) {
    return convert(markdown, null);
  }

  /**
   * Converts markdown to a full HTML email. When {@code frontendBaseUrl} is non-blank, appends a
   * footer with a single link back to the app (past briefings + preference/email settings).
   */
  public static String convert(String markdown, String frontendBaseUrl) {
    Node document = PARSER.parse(markdown != null ? markdown : "");
    String body = RENDERER.render(document);
    return wrapHtml(body + buildFooter(frontendBaseUrl));
  }

  private static String buildFooter(String frontendBaseUrl) {
    if (frontendBaseUrl == null || frontendBaseUrl.isBlank()) {
      return "";
    }
    String url = frontendBaseUrl.strip();
    if (url.endsWith("/")) {
      url = url.substring(0, url.length() - 1);
    }
    return "<hr/>"
        + "<p style=\"font-size:13px;color:#6b7280;margin-top:24px;\">"
        + "오늘의 브리핑은 여기까지예요. 지난 브리핑 다시 보기와 브리핑 선호도·메일 수신 설정 변경은 "
        + "<a href=\""
        + url
        + "\">Briefy</a>에서 하실 수 있어요."
        + "</p>";
  }

  private static String wrapHtml(String body) {
    return "<!DOCTYPE html>"
        + "<html lang=\"ko\"><head><meta charset=\"UTF-8\">"
        + "<style>"
        + "body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Helvetica,Arial,sans-serif;"
        + "font-size:15px;line-height:1.7;color:#1a1a1a;max-width:680px;margin:0 auto;padding:24px 20px;}"
        + "h1{font-size:22px;font-weight:700;margin-bottom:8px;}"
        + "h2{font-size:17px;font-weight:600;margin-top:28px;margin-bottom:10px;}"
        + "ul,ol{padding-left:20px;margin:8px 0;}"
        + "li{margin:4px 0;}"
        + "a{color:#2563eb;text-decoration:none;}"
        + "a:hover{text-decoration:underline;}"
        + "strong{font-weight:600;}"
        + "p{margin:6px 0;}"
        + "hr{border:none;border-top:1px solid #e5e7eb;margin:20px 0;}"
        + "</style></head><body>"
        + body
        + "</body></html>";
  }
}
