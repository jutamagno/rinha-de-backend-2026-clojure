(ns build
  (:require [clojure.tools.build.api :as b]))

(def basis (b/create-basis {:project "deps.edn"}))
(def jar-file "target/rinha.jar")
(def class-dir "target/classes")

(defn uber [_]
  (b/delete {:path "target"})
  (b/copy-dir {:src-dirs   ["src" "resources"]
               :target-dir class-dir})
  (b/javac {:src-dirs  ["src-java"]
            :class-dir class-dir
            :basis     basis})
  (b/compile-clj {:basis       basis
                  :src-dirs    ["src"]
                  :class-dir   class-dir
                  :compile-opts {:direct-linking true}})
  (b/uber {:class-dir class-dir
           :uber-file jar-file
           :basis     basis
           :main      'rinha.core}))
