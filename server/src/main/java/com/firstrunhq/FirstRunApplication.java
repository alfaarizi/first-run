package com.firstrunhq;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Boots the FirstRun product core, a Spring Modulith monolith. */
@SpringBootApplication
public class FirstRunApplication {

  public static void main(String[] args) {
    SpringApplication.run(FirstRunApplication.class, args);
  }
}
