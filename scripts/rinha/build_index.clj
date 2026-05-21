(ns rinha.build-index
  (:import [java.io FileInputStream FileOutputStream BufferedOutputStream DataOutputStream BufferedInputStream]
           [java.nio ByteBuffer ByteOrder]
           [java.util.zip GZIPInputStream]
           [com.fasterxml.jackson.core JsonFactory JsonToken]))

(def ^:const DIMS  14)
(def ^:const MAGIC (unchecked-int 0x4B444E49))  ; "KDNI"

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

;; ─── KD-tree build ────────────────────────────────────────────────────────────

(defn- left-subtree-size [^long n]
  (if (<= n 1)
    0
    (let [h         (- 63 (Long/numberOfLeadingZeros n))  ; floor(log2 n)
          max-last  (bit-shift-left 1 (dec h))
          full      (dec (bit-shift-left 1 h))
          last-tot  (- n full)
          last-left (min last-tot max-last)]
      (+ (dec max-last) last-left))))

(defn- qs-swap! [^ints idx ^long i ^long j]
  (let [t (aget idx i)]
    (aset idx i (aget idx j))
    (aset idx j t)))

(defn- quickselect!
  "Partially sorts idx[lo..hi] so idx[target] is the target-th smallest
   element by dimension dim in raw-vecs."
  [^ints idx ^shorts raw-vecs lo hi target dim]
  (let [lo     (long lo)
        hi     (long hi)
        target (long target)
        dim    (long dim)]
    (loop [lo lo hi hi]
      (when (< lo hi)
        (let [piv-pos (+ lo (quot (- hi lo) 2))
              piv-val (long (aget raw-vecs (unchecked-add
                                            (unchecked-multiply (long (aget idx piv-pos)) DIMS)
                                            dim)))]
          (qs-swap! idx piv-pos hi)
          (let [store (loop [i lo store lo]
                        (if (= i hi)
                          store
                          (let [v (long (aget raw-vecs (unchecked-add
                                                         (unchecked-multiply (long (aget idx i)) DIMS)
                                                         dim)))]
                            (if (< v piv-val)
                              (do (qs-swap! idx store i)
                                  (recur (inc i) (inc store)))
                              (recur (inc i) store)))))]
            (qs-swap! idx store hi)
            (cond
              (= store target) nil
              (< target store) (recur lo (dec store))
              :else            (recur (inc store) hi))))))))

(defn- build-kdtree!
  "Fills out-vecs and out-lbls in BFS/heap order using quickselect median."
  [N ^shorts raw-vecs ^bytes raw-lbls ^shorts out-vecs ^bytes out-lbls]
  (println "Construindo KD-tree (N=" N ")...")
  (let [indices (let [a (int-array N)] (dotimes [i N] (aset a i i)) a)
        ;; Stack: 4 ints per entry [lo hi bfs-pos depth], max ~50 entries
        stk     (int-array 256)
        top     (volatile! 0)]
    (letfn [(push! [lo hi pos depth]
              (let [t (long @top) b (* t 4)]
                (aset stk (int b)       (int lo))
                (aset stk (int (+ b 1)) (int hi))
                (aset stk (int (+ b 2)) (int pos))
                (aset stk (int (+ b 3)) (int depth))
                (vreset! top (inc t))))]
      (push! 0 (dec N) 0 0)
      (loop [cnt 0]
        (when (pos? @top)
          (let [t  (long (dec @top))
                _  (vreset! top t)
                b  (* t 4)
                lo    (long (aget stk (int b)))
                hi    (long (aget stk (int (+ b 1))))
                pos   (long (aget stk (int (+ b 2))))
                depth (long (aget stk (int (+ b 3))))
                n     (- hi lo -1)
                lsz   (long (left-subtree-size n))
                mid   (+ lo lsz)
                dim   (mod depth DIMS)]
            (quickselect! indices raw-vecs lo hi mid dim)
            (let [orig (long (aget indices mid))
                  vb   (unchecked-multiply orig DIMS)
                  ob   (unchecked-multiply pos  DIMS)]
              (dotimes [j DIMS]
                (aset out-vecs (+ ob j) (aget raw-vecs (+ vb j))))
              (aset out-lbls (int pos) (aget raw-lbls (int orig))))
            (when (< mid hi)
              (push! (inc mid) hi (+ 2 (* 2 pos)) (inc depth)))
            (when (> mid lo)
              (push! lo (dec mid) (inc (* 2 pos)) (inc depth)))
            (when (zero? (mod (inc cnt) 500000))
              (println "  nós:" (inc cnt)))
            (recur (inc cnt)))))))
  (println "KD-tree construído."))

;; ─── Write KDNI index ─────────────────────────────────────────────────────────

(defn- write-index! [N ^shorts out-vecs ^bytes out-lbls out-path]
  (println "Escrevendo" out-path "...")
  (with-open [fos (FileOutputStream. out-path)
              bos (BufferedOutputStream. fos (* 16 1024 1024))
              dos (DataOutputStream. bos)]
    (.writeInt dos MAGIC)
    (.writeInt dos N)
    (let [buf (ByteBuffer/allocate (* N DIMS 2))]
      (.order buf ByteOrder/BIG_ENDIAN)
      (.put (.asShortBuffer buf) out-vecs 0 (* N DIMS))
      (.write bos (.array buf)))
    (.write dos out-lbls 0 N))
  (println "Pronto!" out-path
           (str "(" (.length (java.io.File. out-path)) " bytes)")))

;; ─── Entry point ──────────────────────────────────────────────────────────────

(defn -main [& args]
  (let [gz-path  (or (first args) "../rinha-de-backend-2026/resources/references.json.gz")
        out-path (or (second args) "/data/index.bin")]
    (let [[N raw-vecs raw-lbls] (load-references! gz-path)
          out-vecs (short-array (* N DIMS))
          out-lbls (byte-array N)]
      (build-kdtree! N raw-vecs raw-lbls out-vecs out-lbls)
      (write-index! N out-vecs out-lbls out-path))))
