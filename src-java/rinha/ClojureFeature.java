package rinha;

import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeClassInitialization;

public class ClojureFeature implements Feature {

    @Override
    public void afterRegistration(AfterRegistrationAccess access) {
        // Mark only the clojure.lang Java layer for build-time init.
        // Higher-level Clojure namespaces (clojure.core.server, clojure.spec, etc.)
        // have side-effectful static initializers that fail at build time, so we leave
        // them as run-time init. Calling Class.forName("clojure.lang.RT") here (in the
        // single-threaded afterRegistration phase) bootstraps the runtime before the
        // parallel analysis starts, preventing circular-init deadlocks.
        // Only clojure.lang (the Java layer) needs build-time init.
        // Application code (rinha.*) and higher-level Clojure namespaces init at runtime,
        // safely using the pre-built clojure.lang state from the image heap.
        RuntimeClassInitialization.initializeAtBuildTime("clojure.lang");
        try {
            Class.forName("clojure.lang.RT");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }
}
