(ns rinha.build-index
  (:import [java.io FileInputStream FileOutputStream BufferedOutputStream DataOutputStream]
           [java.util.zip GZIPInputStream]
           [com.fasterxml.jackson.core JsonFactory JsonToken]))

(defn quantize [^double v]
  (if (== v -1.0)
    (short -32768)
    (short (Math/round (* v 32767.0)))))

(defn- read-record!
  "Lê um objeto JSON {\"vector\":[...14...],\"label\":\"...\"}  do parser jp.
   Já consumiu START_OBJECT. Preenche vectors em base e labels[i]."
  [jp vectors labels base i]
  (let [vec14 (double-array 14)
        label (volatile! (byte 0))]
    ;; Itera sobre os campos até END_OBJECT
    (loop [tok (.nextToken jp)]
      (when (not= tok JsonToken/END_OBJECT)
        (let [field (.getCurrentName jp)]
          (.nextToken jp) ; avança para o valor
          (cond
            (= field "vector")
            (do
              (dotimes [j 14]
                (.nextToken jp)
                (aset vec14 j (.getDoubleValue jp)))
              (.nextToken jp)) ; END_ARRAY
            (= field "label")
            (vreset! label (if (= "fraud" (.getText jp)) (byte 1) (byte 0))))
          (recur (.nextToken jp))))) ; próximo campo ou END_OBJECT
    ;; Quantiza e grava
    (dotimes [j 14]
      (aset vectors (+ base j) (quantize (aget vec14 j))))
    (aset labels i @label)))

(defn build-index! [gz-path out-path]
  (println "Lendo" gz-path "...")
  (let [n       3000000
        vectors (short-array (* n 14))
        labels  (byte-array n)
        jf      (JsonFactory.)]
    (with-open [gzis (GZIPInputStream. (FileInputStream. gz-path))]
      (let [jp (.createParser jf gzis)]
        (.nextToken jp) ; START_ARRAY raiz
        (dotimes [i n]
          (when (zero? (mod i 200000))
            (println "  processados:" i))
          (.nextToken jp) ; START_OBJECT
          (read-record! jp vectors labels (* i 14) i))))

    (println "Escrevendo" out-path "...")
    (with-open [dos (DataOutputStream.
                      (BufferedOutputStream.
                        (FileOutputStream. out-path)
                        (* 16 1024 1024)))]
      (.writeInt dos 0x52494E48) ; magic "RINH"
      (.writeInt dos n)
      (dotimes [i (* n 14)]
        (.writeShort dos (aget vectors i)))
      (.write dos labels 0 n)))

  (println "Pronto!" out-path
           (str "(" (.length (java.io.File. out-path)) " bytes)")))

(defn -main [& args]
  (let [gz-path  (or (first args)
                     "../rinha-de-backend-2026/resources/references.json.gz")
        out-path (or (second args) "/data/index.bin")]
    (build-index! gz-path out-path)))
