package com.briefy.global.email;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MarkdownToHtmlConverterTest {

  @Test
  void convert_rendersMarkdownBody() {
    String html = MarkdownToHtmlConverter.convert("## 제목\n본문");
    assertThat(html).contains("<h2>제목</h2>");
    assertThat(html).contains("본문");
  }

  @Test
  void convert_withoutFrontendUrl_hasNoFooterLink() {
    String html = MarkdownToHtmlConverter.convert("## 제목", null);
    assertThat(html).doesNotContain("브리핑 선호도");
    assertThat(html).doesNotContain(">Briefy</a>");
  }

  @Test
  void convert_blankFrontendUrl_hasNoFooter() {
    String html = MarkdownToHtmlConverter.convert("## 제목", "  ");
    assertThat(html).doesNotContain(">Briefy</a>");
  }

  @Test
  void convert_withFrontendUrl_appendsFooterWithSingleLink() {
    String html = MarkdownToHtmlConverter.convert("## 제목", "https://briefy.app");
    assertThat(html).contains("href=\"https://briefy.app\"");
    assertThat(html).contains("지난 브리핑 다시 보기");
    assertThat(html).contains("브리핑 선호도");
    // 링크는 한 번만
    assertThat(html.split(">Briefy</a>", -1)).hasSize(2);
  }

  @Test
  void convert_stripsTrailingSlashFromUrl() {
    String html = MarkdownToHtmlConverter.convert("## 제목", "https://briefy.app/");
    assertThat(html).contains("href=\"https://briefy.app\"");
    assertThat(html).doesNotContain("href=\"https://briefy.app/\"");
  }
}
