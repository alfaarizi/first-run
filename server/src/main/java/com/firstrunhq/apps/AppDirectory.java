package com.firstrunhq.apps;

import java.util.Optional;

/** Looks up an app by its public SDK key, for request authentication at the ingest gateway. */
public interface AppDirectory {

  /** Returns the app owning {@code sdkKey}, or empty when no app has that key. */
  Optional<SdkApp> findBySdkKey(String sdkKey);
}
