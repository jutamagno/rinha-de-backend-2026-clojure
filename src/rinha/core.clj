(ns rinha.core
  (:require [rinha.dataset :as ds]
            [rinha.server :as srv])
  (:gen-class))

(defn -main [& _args]
  (let [port      (Integer/parseInt (or (System/getenv "PORT") "3000"))
        index-path (or (System/getenv "INDEX_PATH") "/data/index.bin")]
    (ds/load-index! index-path)
    (srv/start! port)
    (println "Pronto na porta" port)
    ;; Mantém a JVM viva
    @(promise)))
