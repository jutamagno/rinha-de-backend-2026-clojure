(ns rinha.dataset
  (:import [java.io FileInputStream DataInputStream BufferedInputStream]))

(def ^:const magic-ivf (unchecked-int 0x52494E49))  ; "RINI" v2

(defonce state
  (atom {:centroids nil   ; short[] K×DIMS
         :cl-starts nil   ; int[]   K
         :cl-sizes  nil   ; int[]   K
         :vectors   nil   ; short[] N×DIMS sorted by cluster
         :labels    nil   ; byte[]  N
         :n         0
         :k         0
         :ready?    false}))

(defn loaded? [] (:ready? @state))

(defn load-index!
  [path]
  (println "Carregando índice IVF:" path)
  (with-open [fis (FileInputStream. path)
              bis (BufferedInputStream. fis (* 16 1024 1024))
              dis (DataInputStream. bis)]
    (let [m (.readInt dis)]
      (when (not= m magic-ivf)
        (throw (ex-info "Índice no formato antigo — rebuild necessário"
                        {:magic (format "0x%08X" m)})))
      (let [N (.readInt dis)
            K (.readInt dis)
            _ (println "  N=" N "clusters=" K)
            centroids (short-array (* K 14))
            _ (dotimes [i (* K 14)] (aset centroids i (.readShort dis)))
            cl-starts (int-array K)
            _ (dotimes [k K] (aset cl-starts k (.readInt dis)))
            cl-sizes  (int-array K)
            _ (dotimes [k K] (aset cl-sizes k (.readInt dis)))
            _ (println "  Lendo vetores (" (quot (* N 14 2) 1048576) " MB)...")
            vecs (short-array (* N 14))
            _ (dotimes [i (* N 14)] (aset vecs i (.readShort dis)))
            lbls (byte-array N)
            _ (.readFully dis lbls)]
        (reset! state {:centroids centroids
                       :cl-starts cl-starts
                       :cl-sizes  cl-sizes
                       :vectors   vecs
                       :labels    lbls
                       :n         N
                       :k         K
                       :ready?    true})
        (println "Índice carregado."))))
  nil)
