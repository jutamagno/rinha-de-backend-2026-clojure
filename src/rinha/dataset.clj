(ns rinha.dataset
  (:import [java.io FileInputStream DataInputStream BufferedInputStream]))

(def ^:const magic 0x52494E48)

(defonce state
  (atom {:vectors nil   ; short[]
         :labels  nil   ; byte[]
         :n       0
         :ready?  false}))

(defn loaded? [] (:ready? @state))

(defn load-index!
  "Carrega index.bin em memória. Bloqueia até completar."
  [path]
  (println "Carregando índice:" path)
  (with-open [fis (FileInputStream. path)
              bis (BufferedInputStream. fis (* 16 1024 1024))
              dis (DataInputStream. bis)]
    (let [m (.readInt dis)
          _ (when (not= m magic)
              (throw (ex-info "Magic inválido" {:got m :expected magic})))
          n    (.readInt dis)
          _    (println "  N =" n "vetores")
          size (* n 14)
          vecs (short-array size)
          lbls (byte-array n)]

      ;; Lê N*14 shorts — BufferedInputStream garante leitura eficiente
      (dotimes [i size]
        (aset vecs i (.readShort dis)))

      ;; Lê N labels de uma vez
      (.readFully dis lbls)

      (reset! state {:vectors vecs :labels lbls :n n :ready? true})
      (println "Índice carregado.")))
  nil)
