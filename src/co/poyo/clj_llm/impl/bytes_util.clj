(ns co.poyo.clj-llm.impl.bytes-util
  (:require
   [clojure.java.io :as io])
  (:import
   [java.util Base64]
   [java.io ByteArrayOutputStream]))

(defn bytes->base64
  "Encode byte array to base64 string."
  ^String [^bytes bs]
  (.encodeToString (Base64/getEncoder) bs))

(defn slurp-bytes
  "Read entire file into a byte array."
  ^bytes [path]
  (with-open [in (io/input-stream path)]
    (let [baos (ByteArrayOutputStream.)]
      (io/copy in baos)
      (.toByteArray baos))))
