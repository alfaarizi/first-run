package com.firstrunhq;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.autoconfigure.graphql.tester.AutoConfigureHttpGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;

/**
 * Base composite annotation for integration tests. Identical attributes keep every class on one
 * cached context, so a per-class property or import pays another full stack startup.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = "firstrun.identity.trusted-tenant-header=true")
@AutoConfigureHttpGraphQlTester
@Import(TestcontainersConfiguration.class)
public @interface IntegrationTest {}
