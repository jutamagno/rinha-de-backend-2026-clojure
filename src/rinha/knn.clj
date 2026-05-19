(ns rinha.knn
  (:require [rinha.dataset :as ds]))

(def ^:const k 5)

(defn- dist-sq
  [^ints q ^shorts data base]
  (let [base (long base)]
    (loop [j 0 acc 0]
      (if (= j 14)
        acc
        (let [d (- (aget q j) (long (aget data (unchecked-add base j))))]
          (recur (unchecked-inc j) (unchecked-add acc (unchecked-multiply d d))))))))

(defn- find-max-idx
  [^longs arr]
  (loop [m 1 mi 0]
    (if (= m k)
      mi
      (recur (unchecked-inc m)
             (if (> (aget arr m) (aget arr mi)) m mi)))))

(defn- search-range
  [^ints q ^shorts vectors ^bytes labels start end]
  (let [start     (long start)
        end       (long end)
        best-dist (long-array k Long/MAX_VALUE)
        best-lbl  (byte-array k)
        worst     (volatile! Long/MAX_VALUE)]
    (loop [i start]
      (when (< i end)
        (let [d (long (dist-sq q vectors (* i 14)))]
          (when (< d (long @worst))
            (let [mi (find-max-idx best-dist)]
              (aset best-dist mi d)
              (aset best-lbl  mi (aget labels i))
              (vreset! worst (aget best-dist (find-max-idx best-dist))))))
        (recur (unchecked-inc i))))
    [best-dist best-lbl]))

(defn- merge-results
  [[d1 l1] [d2 l2]]
  (let [^longs d1   d1
        ^bytes l1   l1
        ^longs d2   d2
        ^bytes l2   l2
        best-dist   (long-array k Long/MAX_VALUE)
        best-lbl    (byte-array k)]
    (doseq [[d lbl] (concat (map vector d1 l1) (map vector d2 l2))]
      (let [d  (long d)
            mi (find-max-idx best-dist)]
        (when (< d (aget best-dist mi))
          (aset best-dist mi d)
          (aset best-lbl  mi (unchecked-byte lbl)))))
    [best-dist best-lbl]))

(defn fraud-count
  [^ints q-vec]
  (let [st       @ds/state
        ^shorts  vectors (:vectors st)
        ^bytes   labels  (:labels st)
        n        (long (:n st))
        mid      (quot n 2)
        f1       (future (search-range q-vec vectors labels 0 mid))
        r2       (search-range q-vec vectors labels mid n)
        [_ bl]   (merge-results @f1 r2)
        ^bytes   best-lbl bl]
    (loop [i 0 cnt 0]
      (if (= i k)
        cnt
        (recur (inc i) (+ cnt (if (= 1 (aget best-lbl i)) 1 0)))))))
