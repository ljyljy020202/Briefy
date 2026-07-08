package com.briefy.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CollectionPropertiesTest {

  @Test
  void validProperties_constructsSuccessfully() {
    CollectionProperties props = new CollectionProperties(7, 300, 100, 100, 500);
    assertThat(props.lookbackDays()).isEqualTo(7);
    assertThat(props.discoveryLimitPerSource()).isEqualTo(300);
    assertThat(props.detailFetchLimitPerSource()).isEqualTo(100);
    assertThat(props.maxResultsPerSource()).isEqualTo(100);
    assertThat(props.maxTotalResults()).isEqualTo(500);
  }

  @Test
  void zeroLookbackDays_throwsIllegalArgument() {
    assertThatThrownBy(() -> new CollectionProperties(0, 300, 100, 100, 500))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void zeroDiscoveryLimit_throwsIllegalArgument() {
    assertThatThrownBy(() -> new CollectionProperties(7, 0, 100, 100, 500))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void zeroDetailFetchLimit_throwsIllegalArgument() {
    assertThatThrownBy(() -> new CollectionProperties(7, 300, 0, 100, 500))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void zeroMaxResultsPerSource_throwsIllegalArgument() {
    assertThatThrownBy(() -> new CollectionProperties(7, 300, 100, 0, 500))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void zeroMaxTotalResults_throwsIllegalArgument() {
    assertThatThrownBy(() -> new CollectionProperties(7, 300, 100, 100, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void minimumValueOne_constructsSuccessfully() {
    assertThat(new CollectionProperties(1, 1, 1, 1, 1)).isNotNull();
  }
}
