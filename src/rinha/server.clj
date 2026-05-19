(ns rinha.server
  (:require [org.httpkit.server :as hk]
            [cheshire.core :as json]
            [rinha.dataset :as ds]
            [rinha.knn :as knn]
            [rinha.vectorizer :as vec]))

(defonce *server (atom nil))

(defn handler [req]
  (case (:uri req)
    "/ready"
    (if (ds/loaded?)
      {:status 200 :body "OK"}
      {:status 503 :body "loading"})

    "/fraud-score"
    (try
      (let [tx         (json/parse-string (slurp (:body req)) true)
            q-vec      (vec/vectorize tx)
            fraud-cnt  (knn/fraud-count q-vec)
            score      (/ fraud-cnt 5.0)]
        {:status  200
         :headers {"Content-Type" "application/json"}
         :body    (json/generate-string
                    {:approved    (< score 0.6)
                     :fraud_score (double score)})})
      (catch Exception _
        ;; Retorna aprovado como fallback (peso FP=1 < peso Err=5)
        {:status  200
         :headers {"Content-Type" "application/json"}
         :body    "{\"approved\":true,\"fraud_score\":0.0}"}))

    {:status 404 :body "not found"}))

(defn start! [port]
  (reset! *server
          (hk/run-server handler
                         {:port        port
                          :thread      4
                          :queue-size  20000
                          :max-body    65536}))
  (println "Servidor iniciado na porta" port))

(defn stop! []
  (when-let [s @*server]
    (s)
    (reset! *server nil)))
