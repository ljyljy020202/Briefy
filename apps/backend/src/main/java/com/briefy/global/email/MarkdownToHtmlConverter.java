package com.briefy.global.email;

import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

public final class MarkdownToHtmlConverter {

  private static final Parser PARSER = Parser.builder().build();
  private static final HtmlRenderer RENDERER = HtmlRenderer.builder().build();

  private MarkdownToHtmlConverter() {}

  public static String convert(String markdown) {
    Node document = PARSER.parse(markdown != null ? markdown : "");
    String body = RENDERER.render(document);
    return wrapHtml(body);
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
