(ns rinha.knn
  (:require [rinha.dataset :as ds]))

(def ^:const k      5)   ; k-NN neighbours
(def ^:const nprobe 10)  ; clusters to search per query

;; ─── Centroid selection ───────────────────────────────────────────────────────

(defn- centroid-dist ^long [^ints q ^shorts centroids ^long k-idx]
  (let [base (* k-idx 14)]
    (loop [j 0 acc 0]
      (if (= j 14)
        acc
        (let [d (- (long (aget q j)) (long (aget centroids (+ base j))))]
          (recur (inc j) (unchecked-add acc (unchecked-multiply d d))))))))

(defn- top-clusters
  "Returns int[] of the nprobe nearest centroid indices."
  ^ints [^ints q ^shorts centroids K]
  (let [dists  (long-array K)
        result (int-array nprobe)
        used   (boolean-array K)]
    (dotimes [ci K]
      (aset dists ci (centroid-dist q centroids ci)))
    (dotimes [p nprobe]
      (loop [ci 0  best 0  best-d Long/MAX_VALUE]
        (if (= ci K)
          (do (aset result p best)
              (aset used best true))
          (let [d (aget dists ci)]
            (if (and (not (aget used ci)) (< d best-d))
              (recur (inc ci) ci d)
              (recur (inc ci) best best-d))))))
    result))

;; ─── Cluster search ───────────────────────────────────────────────────────────

(defn- search-cluster
  "Scans vectors[start..end) updating best-dist/best-lbl. Returns new worst dist."
  [^ints q ^shorts vectors ^bytes labels
   start end
   ^longs best-dist ^bytes best-lbl worst-dist]
  (loop [i (long start)  wd (long worst-dist)]
    (if (= i end)
      wd
      (let [base (* i 14)
            d    (loop [j 0 acc 0]
                   (if (= j 14)
                     acc
                     (let [diff (- (long (aget q j)) (long (aget vectors (+ base j))))]
                       (recur (inc j) (unchecked-add acc (unchecked-multiply diff diff))))))]
        (if (< d wd)
          (let [mi (loop [m 1 best-m 0]
                     (if (= m k)
                       best-m
                       (recur (inc m)
                              (if (> (aget best-dist m) (aget best-dist best-m)) m best-m))))]
            (aset best-dist mi d)
            (aset best-lbl  mi (aget labels i))
            (recur (inc i)
                   (loop [m 1 w (aget best-dist 0)]
                     (if (= m k) w
                       (recur (inc m) (if (> (aget best-dist m) w) (aget best-dist m) w))))))
          (recur (inc i) wd))))))

;; ─── Public API ──────────────────────────────────────────────────────────────

(defn fraud-count [^ints q-vec]
  (let [st        @ds/state
        centroids ^shorts (:centroids st)
        vectors   ^shorts (:vectors st)
        labels    ^bytes  (:labels st)
        cl-starts ^ints   (:cl-starts st)
        cl-sizes  ^ints   (:cl-sizes st)
        K         (long (:k st))
        top       (top-clusters q-vec centroids K)
        best-dist (long-array k Long/MAX_VALUE)
        best-lbl  (byte-array k)]
    (loop [p 0  wd Long/MAX_VALUE]
      (when (< p nprobe)
        (let [ci    (aget top p)
              start (long (aget cl-starts ci))
              end   (+ start (long (aget cl-sizes ci)))
              new-wd (search-cluster q-vec vectors labels start end best-dist best-lbl wd)]
          (recur (inc p) new-wd))))
    (loop [i 0 cnt 0]
      (if (= i k)
        cnt
        (recur (inc i) (if (= 1 (aget best-lbl i)) (inc cnt) cnt))))))
