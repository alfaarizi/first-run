package com.firstrunhq;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FirstRunApplicationTests {

  @Test
  void contextLoads() {}
}
