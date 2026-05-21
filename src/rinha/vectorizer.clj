(ns rinha.vectorizer)

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

(def ^:const max-amount          10000.0)
(def ^:const max-installments    12.0)
(def ^:const amount-vs-avg-ratio 10.0)
(def ^:const max-minutes         1440.0)
(def ^:const max-km              1000.0)
(def ^:const max-tx-count-24h    20.0)
(def ^:const max-merchant-avg    10000.0)
(def ^:const sentinel-short      -32768)
(def ^:const scale               32767.0)

(defn ^:private clamp ^double [^double x]
  (if (< x 0.0) 0.0 (if (> x 1.0) 1.0 x)))

(defn ^:private encode ^long [^double v]
  (Math/round ^double (* v scale)))

;; ─── Fast ISO-8601 parser ─────────────────────────────────────────────────────
;; Handles format "YYYY-MM-DDTHH:MM:SSZ" (UTC, no fractional seconds)

(defn- c ^long [^String s ^long i]
  (- (long (.charAt s i)) 48))

(defn- d2 ^long [^String s ^long i]
  (+ (* 10 (c s i)) (c s (inc i))))

(defn- d4 ^long [^String s]
  (+ (* 1000 (c s 0)) (* 100 (c s 1)) (* 10 (c s 2)) (c s 3)))

;; Returns days since Unix epoch (1970-01-01) using Howard Hinnant's algorithm.
;; Works for any Gregorian date, exact for all eras.
(defn- ts-days ^long [^String ts]
  (let [y0 (d4 ts)
        m0 (d2 ts 5)
        d  (d2 ts 8)
        y  (if (<= m0 2) (dec y0) y0)
        m  (if (<= m0 2) (+ m0 12) m0)
        era (quot y 400)
        yoe (- y (* era 400))
        doy (+ (quot (+ (* 153 (- m 3)) 2) 5) (dec d))
        doe (+ (* yoe 365) (quot yoe 4) (- (quot yoe 100)) doy)]
    (- (+ (* era 146097) doe) 719468)))

(defn- ts-epoch-sec ^long [^String ts]
  (let [days (ts-days ts)]
    (+ (* days 86400) (* (d2 ts 11) 3600) (* (d2 ts 14) 60) (d2 ts 17))))

;; ─── Vectorize ────────────────────────────────────────────────────────────────

(defn vectorize [tx]
  (let [transaction   (:transaction tx)
        customer      (:customer tx)
        merchant      (:merchant tx)
        terminal      (:terminal tx)
        last-tx       (:last_transaction tx)
        amount        (double (:amount transaction))
        installments  (long (:installments transaction))
        req-at        ^String (:requested_at transaction)
        req-days      (ts-days req-at)
        hour          (d2 req-at 11)
        dow           (mod (+ req-days 3) 7)   ; 0=Mon 6=Sun
        avg-amount    (double (:avg_amount customer))
        tx-count-24h  (long (:tx_count_24h customer))
        merc-id       (:id merchant)
        mcc           (:mcc merchant)
        merc-avg      (double (:avg_amount merchant))
        is-online     (boolean (:is_online terminal))
        card-present  (boolean (:card_present terminal))
        km-home       (double (:km_from_home terminal))
        known-mercs   ^java.util.Collection (:known_merchants customer)
        [dim5 dim6]   (if last-tx
                        (let [mins (/ (- (ts-epoch-sec req-at)
                                        (ts-epoch-sec ^String (:timestamp last-tx)))
                                     60.0)]
                          [(encode (clamp (/ mins max-minutes)))
                           (encode (clamp (/ (double (:km_from_current last-tx)) max-km)))])
                        [sentinel-short sentinel-short])]
    (int-array
      [(encode (clamp (/ amount max-amount)))
       (encode (clamp (/ installments max-installments)))
       (encode (clamp (/ (/ amount avg-amount) amount-vs-avg-ratio)))
       (encode (/ (double hour) 23.0))
       (encode (/ (double dow) 6.0))
       dim5
       dim6
       (encode (clamp (/ km-home max-km)))
       (encode (clamp (/ tx-count-24h max-tx-count-24h)))
       (if is-online    32767 0)
       (if card-present 32767 0)
       (if (.contains known-mercs merc-id) 0 32767)
       (encode (get mcc-risk mcc 0.5))
       (encode (clamp (/ merc-avg max-merchant-avg)))])))

(defn vectorize*
  "Takes extracted field values directly — called from the streaming JSON parser.
   Avoids all intermediate map and keyword allocation on the hot path."
  [amount installments ^String req-at
   avg-amount tx-count-24h ^java.util.Collection known-mercs
   ^String merc-id ^String mcc merc-avg
   is-online card-present km-home
   last-ts last-km-from-current]
  (let [amount       (double amount)
        installments (long   installments)
        avg-amount   (double avg-amount)
        tx-count-24h (long   tx-count-24h)
        merc-avg     (double merc-avg)
        km-home      (double km-home)
        last-km      (double last-km-from-current)
        req-days     (ts-days req-at)
        hour         (d2 req-at 11)
        dow          (mod (+ req-days 3) 7)
        dim5         (if last-ts
                       (encode (clamp (/ (- (ts-epoch-sec req-at)
                                           (ts-epoch-sec ^String last-ts))
                                        (* 60.0 max-minutes))))
                       sentinel-short)
        dim6         (if last-ts
                       (encode (clamp (/ last-km max-km)))
                       sentinel-short)]
    (int-array
      [(encode (clamp (/ amount max-amount)))
       (encode (clamp (/ (double installments) max-installments)))
       (encode (clamp (/ (/ amount avg-amount) amount-vs-avg-ratio)))
       (encode (/ (double hour) 23.0))
       (encode (/ (double dow) 6.0))
       dim5
       dim6
       (encode (clamp (/ km-home max-km)))
       (encode (clamp (/ (double tx-count-24h) max-tx-count-24h)))
       (if is-online    32767 0)
       (if card-present 32767 0)
       (if (.contains known-mercs merc-id) 0 32767)
       (encode (get mcc-risk mcc 0.5))
       (encode (clamp (/ merc-avg max-merchant-avg)))])))
