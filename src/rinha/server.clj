(ns rinha.server
  (:require [org.httpkit.server :as hk]
            [rinha.dataset :as ds]
            [rinha.knn :as knn]
            [rinha.parser :as parser]))

(defonce *server (atom nil))

(defn handler [req]
  (case (:uri req)
    "/ready"
    (if (ds/loaded?)
      {:status 200 :body "OK"}
      {:status 503 :body "loading"})

    "/fraud-score"
    (try
      (let [q-vec     (parser/parse-and-vectorize! (:body req))
            fraud-cnt (knn/fraud-count q-vec)
            score     (/ (double fraud-cnt) 5.0)
            approved  (<= score 0.2)]
        {:status  200
         :headers {"Content-Type" "application/json"}
         :body    (str "{\"approved\":" approved ",\"fraud_score\":" score "}")})
      (catch Exception _
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
