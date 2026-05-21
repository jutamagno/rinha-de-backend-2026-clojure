(ns rinha.parser
  (:require [rinha.vectorizer :as vec])
  (:import [com.fasterxml.jackson.core JsonFactory JsonToken JsonParser]
           [java.util ArrayList]
           [java.io InputStream]))

; Lazy init so GraalVM native image doesn't snapshot a build-time JsonFactory.
; AUTO_CLOSE_SOURCE=false stops Jackson from closing the http-kit InputStream.
(def ^:private factory
  (delay (doto (JsonFactory.)
           (.configure com.fasterxml.jackson.core.JsonParser$Feature/AUTO_CLOSE_SOURCE false))))

(defn parse-and-vectorize!
  "Parses the /fraud-score JSON payload from an InputStream using Jackson streaming.
   Extracts only the 13 fields needed by vectorize* — no map or keyword allocation."
  ^ints [^InputStream is]
  (let [^JsonParser jp (.createParser ^JsonFactory @factory is)]
    (let [^doubles  tx-amount       (double-array  1)
          ^longs    tx-installments (long-array    1)
          ^objects  tx-req-at       (object-array  1)
          ^doubles  cust-avg        (double-array  1)
          ^longs    cust-count      (long-array    1)
          cust-mercs                (ArrayList.)
          ^objects  merc-id         (object-array  1)
          ^objects  merc-mcc        (object-array  1)
          ^doubles  merc-avg        (double-array  1)
          ^booleans term-online     (boolean-array 1)
          ^booleans term-present    (boolean-array 1)
          ^doubles  term-km         (double-array  1)
          ^objects  last-ts         (object-array  1)
          ^doubles  last-km         (double-array  1)]
      (.nextToken jp) ; ROOT START_OBJECT
      (loop []
        (when (= (.nextToken jp) JsonToken/FIELD_NAME)
          (case (.getCurrentName jp)

            "transaction"
            (do (.nextToken jp) ; START_OBJECT
                (loop []
                  (when (= (.nextToken jp) JsonToken/FIELD_NAME)
                    (case (.getCurrentName jp)
                      "amount"       (do (.nextToken jp) (aset tx-amount       0 (.getDoubleValue jp)))
                      "installments" (do (.nextToken jp) (aset tx-installments 0 (.getLongValue jp)))
                      "requested_at" (do (.nextToken jp) (aset tx-req-at       0 (.getText jp)))
                      (do (.nextToken jp) (.skipChildren jp)))
                    (recur))))

            "customer"
            (do (.nextToken jp) ; START_OBJECT
                (loop []
                  (when (= (.nextToken jp) JsonToken/FIELD_NAME)
                    (case (.getCurrentName jp)
                      "avg_amount"   (do (.nextToken jp) (aset cust-avg   0 (.getDoubleValue jp)))
                      "tx_count_24h" (do (.nextToken jp) (aset cust-count 0 (.getLongValue jp)))
                      "known_merchants"
                      (do (.nextToken jp) ; START_ARRAY
                          (loop []
                            (when (= (.nextToken jp) JsonToken/VALUE_STRING)
                              (.add cust-mercs (.getText jp))
                              (recur))))
                      (do (.nextToken jp) (.skipChildren jp)))
                    (recur))))

            "merchant"
            (do (.nextToken jp) ; START_OBJECT
                (loop []
                  (when (= (.nextToken jp) JsonToken/FIELD_NAME)
                    (case (.getCurrentName jp)
                      "id"         (do (.nextToken jp) (aset merc-id  0 (.getText jp)))
                      "mcc"        (do (.nextToken jp) (aset merc-mcc 0 (.getText jp)))
                      "avg_amount" (do (.nextToken jp) (aset merc-avg 0 (.getDoubleValue jp)))
                      (do (.nextToken jp) (.skipChildren jp)))
                    (recur))))

            "terminal"
            (do (.nextToken jp) ; START_OBJECT
                (loop []
                  (when (= (.nextToken jp) JsonToken/FIELD_NAME)
                    (case (.getCurrentName jp)
                      "is_online"    (do (.nextToken jp) (aset term-online  0 (.getBooleanValue jp)))
                      "card_present" (do (.nextToken jp) (aset term-present 0 (.getBooleanValue jp)))
                      "km_from_home" (do (.nextToken jp) (aset term-km      0 (.getDoubleValue jp)))
                      (do (.nextToken jp) (.skipChildren jp)))
                    (recur))))

            "last_transaction"
            (do (.nextToken jp) ; START_OBJECT or VALUE_NULL
                (when (= (.currentToken jp) JsonToken/START_OBJECT)
                  (loop []
                    (when (= (.nextToken jp) JsonToken/FIELD_NAME)
                      (case (.getCurrentName jp)
                        "timestamp"       (do (.nextToken jp) (aset last-ts 0 (.getText jp)))
                        "km_from_current" (do (.nextToken jp) (aset last-km 0 (.getDoubleValue jp)))
                        (do (.nextToken jp) (.skipChildren jp)))
                      (recur)))))

            ;; unknown root field (e.g. "id") — skip value
            (do (.nextToken jp) (.skipChildren jp)))
          (recur)))

      (vec/vectorize*
        (aget tx-amount 0)
        (aget tx-installments 0)
        (aget tx-req-at 0)
        (aget cust-avg 0)
        (aget cust-count 0)
        cust-mercs
        (aget merc-id 0)
        (aget merc-mcc 0)
        (aget merc-avg 0)
        (aget term-online 0)
        (aget term-present 0)
        (aget term-km 0)
        (aget last-ts 0)
        (aget last-km 0)))))
