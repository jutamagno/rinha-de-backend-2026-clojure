(ns rinha.vectorizer)

;; mcc_risk.json hard-coded — evita I/O no runtime
(def mcc-risk
  {"5411" 0.15
   "5812" 0.30
   "5912" 0.20
   "5944" 0.45
   "7801" 0.80
   "7802" 0.75
   "7995" 0.85
   "4511" 0.35
   "5311" 0.25
   "5999" 0.50})

;; normalization constants
(def ^:const max-amount             10000.0)
(def ^:const max-installments       12.0)
(def ^:const amount-vs-avg-ratio    10.0)
(def ^:const max-minutes            1440.0)
(def ^:const max-km                 1000.0)
(def ^:const max-tx-count-24h       20.0)
(def ^:const max-merchant-avg       10000.0)

(def ^:const sentinel-short -32768)
(def ^:const scale          32767.0)

(defn ^:private clamp ^double [^double x]
  (if (< x 0.0) 0.0 (if (> x 1.0) 1.0 x)))

(defn ^:private encode ^long [^double v]
  (Math/round ^double (* v scale)))

(defn ^:private minutes-between
  "Minutos entre prev-ts e current-ts (positivo quando prev-ts é anterior)."
  ^double [^String prev-ts ^String current-ts]
  (let [prev    (java.time.Instant/parse prev-ts)
        current (java.time.Instant/parse current-ts)
        dur     (java.time.Duration/between prev current)]
    (/ (.toSeconds dur) 60.0)))

(defn vectorize
  "Transforma o payload de transação em um int[] de 14 dimensões quantizadas.
   Usa int (não short) para aritmética segura no KNN."
  [tx]
  (let [transaction      (:transaction tx)
        customer         (:customer tx)
        merchant         (:merchant tx)
        terminal         (:terminal tx)
        last-tx          (:last_transaction tx)
        amount           (double (:amount transaction))
        installments     (long (:installments transaction))
        requested-at     ^String (:requested_at transaction)
        instant          (java.time.Instant/parse requested-at)
        zdt              (.atZone instant java.time.ZoneOffset/UTC)
        hour             (.getHour zdt)
        dow              (-> (.getDayOfWeek zdt) .getValue dec) ; Mon=0 Sun=6
        avg-amount       (double (:avg_amount customer))
        tx-count-24h     (long (:tx_count_24h customer))
        known-merchants  (set (:known_merchants customer))
        merc-id          (:id merchant)
        mcc              (:mcc merchant)
        merc-avg         (double (:avg_amount merchant))
        is-online        (boolean (:is_online terminal))
        card-present     (boolean (:card_present terminal))
        km-home          (double (:km_from_home terminal))
        [dim5 dim6]      (if last-tx
                           [(encode (clamp (/ (minutes-between (:timestamp last-tx) requested-at)
                                             max-minutes)))
                            (encode (clamp (/ (double (:km_from_current last-tx)) max-km)))]
                           [sentinel-short sentinel-short])]
    (int-array
      [(encode (clamp (/ amount max-amount)))
       (encode (clamp (/ installments max-installments)))
       (encode (clamp (/ (/ amount avg-amount) amount-vs-avg-ratio)))
       (encode (/ hour 23.0))
       (encode (/ dow 6.0))
       dim5
       dim6
       (encode (clamp (/ km-home max-km)))
       (encode (clamp (/ tx-count-24h max-tx-count-24h)))
       (if is-online   32767 0)
       (if card-present 32767 0)
       (if (contains? known-merchants merc-id) 0 32767)
       (encode (get mcc-risk mcc 0.5))
       (encode (clamp (/ merc-avg max-merchant-avg)))])))
