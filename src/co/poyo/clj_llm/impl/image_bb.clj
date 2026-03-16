(ns co.poyo.clj-llm.impl.image-bb
  "Babashka image resizing implementation for clj-llm.content.
   Uses ImageMagick if available."
  (:require
    [co.poyo.clj-llm.impl.mime-util :as mime-util]
    [co.poyo.clj-llm.impl.bytes-util :as bytes-util]
   ))

(defn- process-success?
  [^Process proc]
  (zero? (.waitFor proc)))

(defn- find-magick-cmd
  "Return the ImageMagick command available on PATH, or nil."
  []
  (first
   (for [cmd ["magick" "convert"]
         :when (try
                 (let [proc (.start (ProcessBuilder. ^java.util.List [cmd "--version"]))]
                   (process-success? proc))
                 (catch Exception _
                   false))]
     cmd)))

(def ^:private magick-cmd
  (delay (find-magick-cmd)))

(defn- magick-geometry
  "Translate resize opts into ImageMagick geometry."
  [opts]
  (cond
    (:max-edge opts)
    (str (:max-edge opts) "x" (:max-edge opts) ">")

    (and (:max-width opts) (:max-height opts))
    (str (:max-width opts) "x" (:max-height opts) ">")

    (:max-width opts)
    (str (:max-width opts) "x>")

    (:max-height opts)
    (str "x" (:max-height opts) ">")

    :else
    nil))

(defn- output-format
  "Determine output format for ImageMagick."
  [path opts]
  (or (:format opts)
      (if (#{"jpg" "jpeg"} (mime-util/file-extension path))
        "jpeg"
        "png")))

(defn- read-stderr
  ^String [^Process proc]
  (slurp (.getErrorStream proc)))

(defn resize-image
  "Resize an image using ImageMagick.

   Returns:
     {:data <base64> :media-type <string>}

   Returns nil if:
   - ImageMagick is unavailable
   - no resize geometry is requested
   - the command fails
   - no bytes are produced"
  [path opts]
  (when-let [cmd @magick-cmd]
    (when-let [geom (magick-geometry opts)]
      (let [fmt      (output-format path opts)
            out-mime (if (= fmt "jpeg") "image/jpeg" "image/png")
            args     [cmd
                      (str path)
                      "-resize" geom
                      (str fmt ":-")]
            proc     (.start (ProcessBuilder. ^java.util.List args))
            bs       (.readAllBytes (.getInputStream proc))
            ok?      (process-success? proc)]
        (when-not ok?
          (binding [*out* *err*]
            (println "clj-llm: ImageMagick resize failed:"
                     (read-stderr proc))))
        (when (and ok? (pos? (alength bs)))
          {:data       (bytes-util/bytes->base64 bs)
           :media-type out-mime})))))
