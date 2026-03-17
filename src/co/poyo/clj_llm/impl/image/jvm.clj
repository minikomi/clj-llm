(ns co.poyo.clj-llm.impl.image.jvm
  "JVM image resizing implementation for clj-llm.content."
  (:require
   [co.poyo.clj-llm.impl.mime-util :as mime-util]
   [co.poyo.clj-llm.impl.bytes-util :as bytes-util]
   )
  (:import
   [java.io File ByteArrayOutputStream]
   [java.awt.image BufferedImage]
   [java.awt RenderingHints]
   [javax.imageio ImageIO]))

(defn- compute-scale
  "Compute scale factor from resize opts. Never upscale."
  ^double [^long w ^long h opts]
  (let [scales (cond-> [1.0]
                 (:max-edge opts)
                 (conj (/ (double (:max-edge opts))
                          (double (max w h))))

                 (:max-width opts)
                 (conj (/ (double (:max-width opts))
                          (double w)))

                 (:max-height opts)
                 (conj (/ (double (:max-height opts))
                          (double h))))]
    (min 1.0 (apply min scales))))

(defn- output-format
  "Determine output format for ImageIO."
  ^String [opts path]
  (or (:format opts)
      (case (mime-util/file-extension path)
        ("jpg" "jpeg") "jpeg"
        "png" "png"
        ;; safest default if caller asked for resize on some other image type
        "png")))

(defn resize-image
  "Resize an image using javax.imageio + java.awt.

   Returns:
     {:data <base64> :width <int> :height <int> :media-type <string>}

   Returns nil if the image cannot be decoded."
  [path opts]
  (let [file (File. (str path))
        img  (ImageIO/read file)]
    (when img
      (let [w       (.getWidth img)
            h       (.getHeight img)
            scale   (compute-scale w h opts)
            tw      (max 1 (int (* w scale)))
            th      (max 1 (int (* h scale)))
            fmt     (output-format opts path)
            bi-type (if (= fmt "jpeg")
                      BufferedImage/TYPE_INT_RGB
                      BufferedImage/TYPE_INT_ARGB)
            dst     (BufferedImage. tw th bi-type)
            g       (.createGraphics dst)]
        (try
          (.setRenderingHint g
                             RenderingHints/KEY_INTERPOLATION
                             RenderingHints/VALUE_INTERPOLATION_BICUBIC)
          (.setRenderingHint g
                             RenderingHints/KEY_RENDERING
                             RenderingHints/VALUE_RENDER_QUALITY)
          (.setRenderingHint g
                             RenderingHints/KEY_ANTIALIASING
                             RenderingHints/VALUE_ANTIALIAS_ON)
          (.drawImage g img 0 0 tw th nil)
          (let [baos (ByteArrayOutputStream.)]
            (ImageIO/write dst ^String fmt baos)
            {:data       (bytes-util/bytes->base64 (.toByteArray baos))
             :width      tw
             :height     th
             :media-type (if (= fmt "jpeg")
                           "image/jpeg"
                           "image/png")})
          (finally
            (.dispose g)))))))
