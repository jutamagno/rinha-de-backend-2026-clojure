package rinha;

import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeClassInitialization;

public class ClojureFeature implements Feature {

    @Override
    public void afterRegistration(AfterRegistrationAccess access) {
        RuntimeClassInitialization.initializeAtBuildTime("clojure.lang");
        try {
            Class<?> rt = Class.forName("clojure.lang.RT");
            rt.getMethod("init").invoke(null);
            // Pre-load all namespaces in dependency order before GraalVM's analysis
            // begins. GraalVM's force-init (triggered by --initialize-at-build-time=*)
            // calls <clinit> directly without Clojure's loading context, which fails
            // for most Clojure sub-namespaces. Loading via RT.load uses the proper
            // Clojure mechanism, so GraalVM's later force-init checks find them done.
            rt.getMethod("load", String.class).invoke(null, "cheshire/core");
            // httpkit must be loaded before rinha/core so requiring-resolve in
            // rinha.server finds the namespace already loaded at runtime.
            rt.getMethod("load", String.class).invoke(null, "org/httpkit/server");
            rt.getMethod("load", String.class).invoke(null, "rinha/core");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
