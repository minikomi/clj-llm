(ns co.poyo.clj-llm.content
  "Content parts for multimodal messages — images, PDFs, text.

   Each constructor returns a content-part map that can be mixed into messages:

     (generate ai [\"Describe this\" (content/image \"photo.jpg\" {:max-edge 512})])

   On JVM Clojure, image resizing uses javax.imageio directly.
   On babashka, image resizing requires ImageMagick (convert/magick on PATH).
   Without ImageMagick, images are sent at original size."
  #?@(:bb  [(:require [babashka.process :as proc])]
      :clj [])
  #?@(:clj [(:import
              [java.util Base64]
              [java.io File ByteArrayOutputStream]
              [java.awt.image BufferedImage]
              [java.awt RenderingHints]
              [javax.imageio ImageIO]
              [java.net URL])]
      :bb  [(:import
              [java.util Base64]
              [java.io File])]))

;; ════════════════════════════════════════════════════════════════════
;; Mime type detection
;; ════════════════════════════════════════════════════════════════════

(def ^:private ext->mime
  {"jpg"  "image/jpeg"
   "jpeg" "image/jpeg"
   "png"  "image/png"
   "gif"  "image/gif"
   "webp" "image/webp"
   "pdf"  "application/pdf"})

(defn- extension [path]
  (let [s (str path)
        i (.lastIndexOf s ".")]
    (when (pos? i)
      (.toLowerCase (.substring s (inc i))))))

(defn- mime-from-path [path]
  (get ext->mime (extension path) "application/octet-stream"))

;; ════════════════════════════════════════════════════════════════════
;; Base64 + I/O (both platforms support java.util.Base64 and java.nio)
;; ════════════════════════════════════════════════════════════════════

(defn- bytes->base64 ^String [^bytes bs]
  (.encodeToString (Base64/getEncoder) bs))

(defn- slurp-bytes ^bytes [path]
  (java.nio.file.Files/readAllBytes
   (.toPath (File. (str path)))))

;; ════════════════════════════════════════════════════════════════════
;; Platform-specific image resize
;; ════════════════════════════════════════════════════════════════════

#?(:clj
   (do
     (defn- compute-scale ^double [^long w ^long h opts]
       (let [scales (cond-> [1.0]
                     (:max-edge opts)   (conj (/ (double (:max-edge opts))   (max w h)))
                     (:max-width opts)  (conj (/ (double (:max-width opts))  w))
                     (:max-height opts) (conj (/ (double (:max-height opts)) h)))]
         (min 1.0 (apply min scales))))

     (defn- image-format ^String [opts path]
       (or (:format opts)
           (case (extension path)
             ("jpg" "jpeg") "jpeg"
             "png")))

     (defn- resize-on-jvm
       "Resize an image using javax.imageio + java.awt.
        Returns {:data :media-type :width :height}."
       [path opts]
       (let [img  (ImageIO/read (File. (str path)))]
         (when-not img
           (throw (ex-info (str "Cannot read image: " path)
                           {:error-type :llm/invalid-request :path (str path)})))
         (let [w    (.getWidth img)
               h    (.getHeight img)
               sc   (compute-scale w h opts)
               tw   (max 1 (int (* w sc)))
               th   (max 1 (int (* h sc)))
               fmt  (image-format opts path)
               type (if (= fmt "jpeg") BufferedImage/TYPE_INT_RGB BufferedImage/TYPE_INT_ARGB)
               dst  (BufferedImage. tw th type)
               g    (.createGraphics dst)]
           (.setRenderingHint g RenderingHints/KEY_INTERPOLATION
                              RenderingHints/VALUE_INTERPOLATION_BICUBIC)
           (.setRenderingHint g RenderingHints/KEY_RENDERING
                              RenderingHints/VALUE_RENDER_QUALITY)
           (.setRenderingHint g RenderingHints/KEY_ANTIALIASING
                              RenderingHints/VALUE_ANTIALIAS_ON)
           (.drawImage g img 0 0 tw th nil)
           (.dispose g)
           (let [baos (ByteArrayOutputStream.)]
             (ImageIO/write dst fmt baos)
             {:data       (bytes->base64 (.toByteArray baos))
              :media-type (if (= fmt "jpeg") "image/jpeg" "image/png")
              :width      tw
              :height     th}))))))

#?(:bb
   (do
     (defn- find-magick-cmd
       "Returns the first available ImageMagick command, or nil."
       []
       (first
        (for [cmd  ["magick" "convert"]
              :let [ok? (try (-> (ProcessBuilder. [cmd "--version"]) .start .waitFor zero?)
                            (catch Exception _ false))]
              :when ok?]
          cmd)))

     (def ^:private magick-cmd (delay (find-magick-cmd)))

     (defn- magick-geometry
       "Build an ImageMagick geometry string, or nil when no resize is needed."
       [opts]
       (cond
         (:max-edge opts)                            (str (:max-edge opts) "x" (:max-edge opts) ">")
         (and (:max-width opts) (:max-height opts))  (str (:max-width opts) "x" (:max-height opts) ">")
         (:max-width opts)                           (str (:max-width opts) "x>")
         (:max-height opts)                          (str "x" (:max-height opts) ">")))

     (defn- resize-with-magick
       "Resize using ImageMagick. Returns {:data :media-type}, or nil if unavailable."
       [path opts]
       (when-let [cmd @magick-cmd]
         (let [geom (magick-geometry opts)
               fmt  (or (:format opts) (if (#{"jpg" "jpeg"} (extension path)) "jpeg" "png"))
               args (cond-> [cmd (str path)]
                     geom (into ["-resize" geom])
                     true (conj (str fmt ":-")))
               proc (.start (ProcessBuilder. ^java.util.List args))
               bs   (.readAllBytes (.getInputStream proc))]
           (.waitFor proc)
           (when (pos? (alength bs))
             {:data       (bytes->base64 bs)
              :media-type (if (= fmt "jpeg") "image/jpeg" "image/png")}))))

     (defn- resize-on-bb
       "Resize via ImageMagick, falling back to passthrough with a warning."
       [path opts]
       (or (resize-with-magick path opts)
           (do (binding [*out* *err*]
                 (println "clj-llm: ImageMagick not found — sending image at original size."
                          "(Install: apt install imagemagick / brew install imagemagick)"))
               nil)))))

;; ════════════════════════════════════════════════════════════════════
;; Unified resize/encode  (single call-site for both platforms)
;; ════════════════════════════════════════════════════════════════════

(defn- resize-image
  "Resize a file path. Returns {:data :media-type [:width :height]}."
  [path opts]
  (or #?(:clj (resize-on-jvm path opts)
         :bb  (resize-on-bb  path opts))
      {:data       (bytes->base64 (slurp-bytes path))
       :media-type (mime-from-path path)}))

(defn- encode-image
  "Base64-encode an image file without resizing."
  [path]
  {:data       (bytes->base64 (slurp-bytes path))
   :media-type (mime-from-path path)})

;; ════════════════════════════════════════════════════════════════════
;; Content-part predicate
;; ════════════════════════════════════════════════════════════════════

(defn content-part?
  "Returns true if x is a content part (image, pdf, or text)."
  [x]
  (boolean (and (map? x) (#{:image :pdf :text} (:type x)))))

;; ════════════════════════════════════════════════════════════════════
;; Public API
;; ════════════════════════════════════════════════════════════════════

(defn text
  "Create a text content part.

     (text \"What's in this image?\")"
  [s]
  {:type :text :text (str s)})

(defn image
  "Create an image content part. Source can be:

   - File path (String or File) — read, optionally resized, base64-encoded
   - URL string (http/https)    — passed by URL reference, or downloaded to resize
   - byte array + media-type   — raw bytes

   Resize opts (file and URL sources):
     :max-edge   N           scale so max(w,h) ≤ N (preserves aspect ratio)
     :max-width  N           scale so width ≤ N
     :max-height N           scale so height ≤ N
     :format     \"png\"/\"jpeg\"
     :quality    85          JPEG quality (JVM only)

   Examples:
     (image \"photo.jpg\")
     (image \"photo.jpg\" {:max-edge 1024})
     (image \"https://example.com/img.png\")
     (image \"https://example.com/img.png\" {:max-edge 512})
     (image some-bytes \"image/png\")"
  ([source]
   (image source {}))

  ([source opts-or-media-type]
   (cond
     ;; Raw bytes + explicit media-type string
     (and (bytes? source) (string? opts-or-media-type))
     {:type       :image
      :source     :base64
      :media-type opts-or-media-type
      :data       (bytes->base64 source)}

     ;; URL
     (and (string? source)
          (or (.startsWith ^String source "http://")
              (.startsWith ^String source "https://")))
     (let [opts        (if (map? opts-or-media-type) opts-or-media-type {})
           has-resize? (some opts [:max-edge :max-width :max-height])]
       (if has-resize?
         ;; Download to a temp file, resize, return base64
         (let [ext  (or (some-> #?(:clj  (-> (URL. source) .getPath extension)
                                   :bb   (-> (java.net.URL. source) .getPath extension))
                                not-empty)
                        "png")
               tmp  (doto (File/createTempFile "clj-llm-" (str "." ext))
                      (.deleteOnExit))]
           #?(:clj (with-open [in  (.openStream (URL. source))
                               out (java.io.FileOutputStream. tmp)]
                     (.transferTo in out))
              :bb  (with-open [in  (.openStream (java.net.URL. source))
                               out (java.io.FileOutputStream. tmp)]
                     (.transferTo in out)))
           (let [result (resize-image (str tmp) opts)]
             {:type       :image
              :source     :base64
              :media-type (:media-type result)
              :data       (:data result)}))
         ;; No resize — pass URL through
         {:type   :image
          :source :url
          :url    source}))

     ;; File path (String or File)
     :else
     (let [opts        (if (map? opts-or-media-type) opts-or-media-type {})
           has-resize? (some opts [:max-edge :max-width :max-height])
           result      (if has-resize?
                         (resize-image (str source) opts)
                         (encode-image (str source)))]
       {:type       :image
        :source     :base64
        :media-type (:media-type result)
        :data       (:data result)}))))

(defn pdf
  "Create a PDF content part from a file path.

     (pdf \"invoice.pdf\")"
  [path]
  {:type       :pdf
   :media-type "application/pdf"
   :data       (bytes->base64 (slurp-bytes (str path)))})
