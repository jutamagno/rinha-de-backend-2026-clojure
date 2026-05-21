(ns build
  (:require [clojure.tools.build.api :as b]))

(def basis (b/create-basis {:project "deps.edn"}))
(def jar-file "target/rinha.jar")
(def class-dir "target/classes")

(defn uber [_]
  (b/delete {:path "target"})
  (b/copy-dir {:src-dirs   ["src" "resources"]
               :target-dir class-dir})
  ;; Compile only KDTree.java (ClojureFeature.java has GraalVM-only imports
  ;; and is compiled separately in the native-image stage)
  (b/process {:command-args ["javac" "-d" class-dir "src-java/rinha/KDTree.java"]})
  (b/compile-clj {:basis       basis
                  :src-dirs    ["src"]
                  :class-dir   class-dir
                  :compile-opts {:direct-linking true}})
  (b/uber {:class-dir class-dir
           :uber-file jar-file
           :basis     basis
           :main      'rinha.core}))
