(ns rinha.dataset
  (:import [java.io FileInputStream DataInputStream BufferedInputStream]
           [java.nio ByteBuffer ByteOrder]))

(def ^:const magic-kd (unchecked-int 0x4B444E49))  ; "KDNI"

(defonce state
  (atom {:vectors nil   ; short[] N×DIMS in BFS/heap KD-tree order
         :labels  nil   ; byte[]  N in BFS/heap order
         :n       0
         :ready?  false}))

(defn loaded? [] (:ready? @state))

(defn load-index! [path]
  (println "Carregando índice KD-tree:" path)
  (with-open [fis (FileInputStream. path)
              bis (BufferedInputStream. fis (* 16 1024 1024))
              dis (DataInputStream. bis)]
    (let [m (.readInt dis)]
      (when (not= m magic-kd)
        (throw (ex-info "Índice inválido — rebuild necessário"
                        {:magic (format "0x%08X" m)})))
      (let [N    (.readInt dis)
            _    (println "  N=" N)
            _    (println "  Lendo vetores (" (quot (* N 14 2) 1048576) " MB)...")
            ;; Read in 128 KB chunks to avoid allocating a 84 MB temp byte array
            vecs    (short-array (* N 14))
            chunk   65536  ; shorts per chunk
            raw-buf (byte-array (* chunk 2))
            _       (loop [pos 0 rem (* N 14)]
                      (when (pos? rem)
                        (let [n-sh (min chunk (int rem))
                              n-by (* n-sh 2)]
                          (.readFully dis raw-buf 0 n-by)
                          (let [bb (doto (ByteBuffer/wrap raw-buf 0 n-by)
                                     (.order ByteOrder/BIG_ENDIAN))]
                            (.get (.asShortBuffer bb) vecs pos n-sh))
                          (recur (+ pos n-sh) (- rem n-sh)))))
            lbls (byte-array N)
            _    (.readFully dis lbls)]
        (reset! state {:vectors vecs
                       :labels  lbls
                       :n       N
                       :ready?  true})
        (println "Índice carregado."))))
  nil)
