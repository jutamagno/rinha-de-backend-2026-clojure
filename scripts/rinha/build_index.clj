(ns rinha.build-index
  (:import [java.io FileInputStream FileOutputStream BufferedOutputStream DataOutputStream]
           [java.util.zip GZIPInputStream]
           [com.fasterxml.jackson.core JsonFactory JsonToken]))

(def ^:const DIMS  14)
(def ^:const K     512)
(def ^:const ITERS 20)
(def ^:const MAGIC (unchecked-int 0x52494E49))  ; "RINI" v2 (IVF)

;; ─── Load references.json.gz ──────────────────────────────────────────────────

(defn- quantize [^double v]
  (if (== v -1.0)
    (short -32768)
    (short (Math/round (* v 32767.0)))))

(defn- read-record!
  [jp ^shorts vectors ^bytes labels base i]
  (let [vec14 (double-array DIMS)
        label (volatile! (byte 0))]
    (loop [tok (.nextToken jp)]
      (when (not= tok JsonToken/END_OBJECT)
        (let [field (.getCurrentName jp)]
          (.nextToken jp)
          (cond
            (= field "vector")
            (do (dotimes [j DIMS]
                  (.nextToken jp)
                  (aset vec14 j (.getDoubleValue jp)))
                (.nextToken jp))
            (= field "label")
            (vreset! label (if (= "fraud" (.getText jp)) (byte 1) (byte 0))))
          (recur (.nextToken jp)))))
    (dotimes [j DIMS]
      (aset vectors (+ base j) (quantize (aget vec14 j))))
    (aset labels i @label)))

(defn- load-references! [gz-path]
  (println "Lendo" gz-path "...")
  (let [N       3000000
        vectors (short-array (* N DIMS))
        labels  (byte-array N)
        jf      (JsonFactory.)]
    (with-open [gzis (GZIPInputStream. (FileInputStream. gz-path))]
      (let [jp (.createParser jf gzis)]
        (.nextToken jp)
        (dotimes [i N]
          (when (zero? (mod i 500000)) (println "  lidos:" i))
          (.nextToken jp)
          (read-record! jp vectors labels (* i DIMS) i))))
    (println "  leitura concluída:" N "vetores")
    [N vectors labels]))

;; ─── K-Means ─────────────────────────────────────────────────────────────────

(defn- assign-parallel!
  "Assigns each of N vectors to nearest centroid. Runs in parallel over threads."
  [N ^shorts vectors ^doubles centroids ^ints assignments]
  (let [n-cpus (.availableProcessors (Runtime/getRuntime))
        chunk  (max 1 (quot N n-cpus))
        tasks  (for [t (range n-cpus)]
                 (let [s (* (long t) chunk)
                       e (min (long N) (* (long (inc t)) chunk))]
                   (future
                     (loop [i s]
                       (when (< i e)
                         (let [vbase (* i DIMS)]
                           (loop [ck 0  bk 0  bd Double/MAX_VALUE]
                             (if (= ck K)
                               (aset assignments i bk)
                               (let [cbase (* ck DIMS)
                                     d     (loop [j 0 acc 0.0]
                                             (if (= j DIMS)
                                               acc
                                               (let [diff (- (double (aget vectors (+ vbase j)))
                                                             (aget centroids (+ cbase j)))]
                                                 (recur (inc j) (+ acc (* diff diff))))))]
                                 (if (< d bd)
                                   (recur (inc ck) ck d)
                                   (recur (inc ck) bk bd))))))
                         (recur (inc i)))))))]
    (run! deref tasks)))

(defn- update-centroids!
  "Recomputes centroids as means of assigned vectors."
  [N ^shorts vectors ^ints assignments ^doubles centroids]
  (let [sums   (double-array (* K DIMS) 0.0)
        counts (int-array K 0)]
    (dotimes [i N]
      (let [k     (aget assignments i)
            vbase (* i DIMS)
            cbase (* k DIMS)]
        (dotimes [j DIMS]
          (aset sums (+ cbase j)
                (+ (aget sums (+ cbase j)) (double (aget vectors (+ vbase j))))))
        (aset counts k (inc (aget counts k)))))
    (dotimes [k K]
      (let [cnt   (long (aget counts k))
            cbase (* k DIMS)]
        (when (pos? cnt)
          (dotimes [j DIMS]
            (aset centroids (+ cbase j)
                  (/ (aget sums (+ cbase j)) (double cnt)))))))))

(defn- kmeans! [N ^shorts vectors]
  (println "K-Means K=" K "iters=" ITERS
           "threads=" (.availableProcessors (Runtime/getRuntime)))
  (let [rng         (java.util.Random. 42)
        centroids   (double-array (* K DIMS))
        assignments (int-array N)]
    (dotimes [k K]
      (let [idx   (.nextInt rng N)
            cbase (* k DIMS)
            vbase (* idx DIMS)]
        (dotimes [j DIMS]
          (aset centroids (+ cbase j) (double (aget vectors (+ vbase j)))))))
    (dotimes [iter ITERS]
      (let [t0 (System/currentTimeMillis)]
        (assign-parallel! N vectors centroids assignments)
        (update-centroids! N vectors assignments centroids)
        (println "  iter" (inc iter) "/" ITERS
                 (str "(" (- (System/currentTimeMillis) t0) "ms)"))))
    [centroids assignments]))

;; ─── Sort by cluster and write ────────────────────────────────────────────────

(defn- write-index!
  [N ^shorts vectors ^bytes labels ^doubles centroids ^ints assignments out-path]
  (println "Ordenando vetores por cluster...")
  (let [cl-sizes  (int-array K)
        cl-starts (int-array K)]
    (dotimes [i N]
      (aset cl-sizes (aget assignments i) (inc (aget cl-sizes (aget assignments i)))))
    (loop [k 0 pos 0]
      (when (< k K)
        (aset cl-starts k pos)
        (recur (inc k) (+ pos (aget cl-sizes k)))))
    (let [sorted-vecs   (short-array (* N DIMS))
          sorted-labels (byte-array N)
          cl-offsets    (aclone cl-starts)]
      (dotimes [i N]
        (let [k   (aget assignments i)
              pos (long (aget cl-offsets k))
              src (* i DIMS)
              dst (* pos DIMS)]
          (dotimes [j DIMS]
            (aset sorted-vecs (+ dst j) (aget vectors (+ src j))))
          (aset sorted-labels pos (aget labels i))
          (aset cl-offsets k (unchecked-inc pos))))
      (println "Escrevendo" out-path "...")
      (with-open [dos (DataOutputStream.
                        (BufferedOutputStream.
                          (FileOutputStream. out-path)
                          (* 16 1024 1024)))]
        (.writeInt dos MAGIC)
        (.writeInt dos N)
        (.writeInt dos K)
        (dotimes [i (* K DIMS)]
          (.writeShort dos (int (Math/round (aget centroids i)))))
        (dotimes [k K] (.writeInt dos (aget cl-starts k)))
        (dotimes [k K] (.writeInt dos (aget cl-sizes k)))
        (dotimes [i (* N DIMS)] (.writeShort dos (aget sorted-vecs i)))
        (.write dos sorted-labels 0 N))
      (println "Pronto!" out-path
               (str "(" (.length (java.io.File. out-path)) " bytes)")))))

;; ─── Entry point ──────────────────────────────────────────────────────────────

(defn -main [& args]
  (let [gz-path  (or (first args) "../rinha-de-backend-2026/resources/references.json.gz")
        out-path (or (second args) "/data/index.bin")]
    (let [[N vectors labels] (load-references! gz-path)
          [centroids assignments] (kmeans! N vectors)]
      (write-index! N vectors labels centroids assignments out-path))))
