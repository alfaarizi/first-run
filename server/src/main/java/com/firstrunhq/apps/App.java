package com.firstrunhq.apps;

import java.util.UUID;

/** The dashboard's view of an app, mirroring {@code App} in api/graphql/apps.graphqls. */
public record App(UUID id, String name) {}
