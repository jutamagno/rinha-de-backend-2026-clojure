(ns rinha.knn
  (:require [rinha.dataset :as ds])
  (:import [rinha KDTree]))

(defn fraud-count [^ints q-vec]
  (let [st   @ds/state
        vecs ^shorts (:vectors st)
        lbls ^bytes  (:labels st)
        N    (int (:n st))]
    (KDTree/fraudCount vecs lbls N q-vec)))
