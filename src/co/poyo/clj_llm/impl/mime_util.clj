(ns co.poyo.clj-llm.impl.mime-util
    "Helpers for inferring media types and encoding content."
    (:require
     [clojure.string :as str]))

(defn file-extension [path]
  (some-> path (str/split #"\.") last str/lower-case))

(defn mime-from-path
  "Infer media type from path file extension."
  [path]
  (get {"jpg" "image/jpeg"
        "jpeg" "image/jpeg"
        "png" "image/png"
        "gif" "image/gif"
        "pdf" "application/pdf"}
       (file-extension path)))
