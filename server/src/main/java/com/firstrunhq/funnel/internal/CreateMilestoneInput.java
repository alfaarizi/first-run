package com.firstrunhq.funnel.internal;

/**
 * Mirrors {@code CreateMilestoneInput} in api/graphql/funnel.graphqls. {@code appId} stays a string
 * so an unparseable id resolves to the same not-found error as an unknown one.
 */
record CreateMilestoneInput(String appId, String name, String title, int position) {}
