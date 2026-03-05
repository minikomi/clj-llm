(ns co.poyo.clj-llm.content
  "Content parts for multimodal messages — images, PDFs, text.

   Each constructor returns a content-part map that can be mixed into messages:

     (generate ai [\"Describe this\" (content/image \"photo.jpg\" {:max-edge 512})])

   On JVM Clojure, image resizing uses javax.imageio directly.
   On babashka, image resizing uses pod-golang-image."
  #?(:bb  (:require [babashka.pods :as pods])
     :clj (:import [java.util Base64]
                   [java.io File FileInputStream ByteArrayOutputStream]
                   [java.awt.image BufferedImage]
                   [java.awt Graphics2D RenderingHints]
                   [javax.imageio ImageIO])))

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
  (get ext->mime (extension path)))

;; ════════════════════════════════════════════════════════════════════
;; Base64 encoding + file I/O
;; ════════════════════════════════════════════════════════════════════

(defn- bytes->base64 ^String [^bytes bs]
  #?(:bb  (.encodeToString (java.util.Base64/getEncoder) bs)
     :clj (.encodeToString (Base64/getEncoder) bs)))

(defn- slurp-bytes ^bytes [path]
  (let [f #?(:bb  (java.io.File. (str path))
             :clj (File. (str path)))
        bs (byte-array (.length f))]
    (with-open [is #?(:bb  (java.io.FileInputStream. f)
                      :clj (FileInputStream. f))]
      (loop [off 0]
        (let [n (.read is bs off (- (alength bs) off))]
          (when (pos? n)
            (recur (+ off n))))))
    bs))

;; ════════════════════════════════════════════════════════════════════
;; JVM: javax.imageio resize
;; ════════════════════════════════════════════════════════════════════

#?(:bb :bb ;; bb: jvm-resize defined below via pod
   :clj
   (do
     (defn- compute-scale
       "Compute the scale factor from resize opts. Never upscales (caps at 1.0)."
       ^double [^long w ^long h opts]
       (let [scales (cond-> [1.0]
                     (:max-edge opts)
                     (conj (/ (double (:max-edge opts)) (double (max w h))))
                     (:max-width opts)
                     (conj (/ (double (:max-width opts)) (double w)))
                     (:max-height opts)
                     (conj (/ (double (:max-height opts)) (double h))))]
         (min 1.0 (apply min scales))))

     (defn- output-format
       "Determine output image format string for ImageIO."
       ^String [opts path]
       (or (:format opts)
           (let [ext (extension path)]
             (case ext
               "jpg"  "jpeg"
               "jpeg" "jpeg"
               "png"  "png"
               "png"))))

     (defn- jvm-resize
       "Resize an image using javax.imageio + java.awt.
        Returns {:data <base64> :width :height :media-type}."
       [path opts]
       (let [file (File. (str path))
             img  (ImageIO/read file)
             _    (when-not img
                    (throw (ex-info (str "Cannot read image: " path)
                                   {:error-type :llm/invalid-request :path (str path)})))
             w    (.getWidth img)
             h    (.getHeight img)
             sc   (compute-scale w h opts)
             tw   (max 1 (int (* w sc)))
             th   (max 1 (int (* h sc)))
             fmt  (output-format opts path)
             bi-type (if (= fmt "jpeg")
                       BufferedImage/TYPE_INT_RGB
                       BufferedImage/TYPE_INT_ARGB)
             dst  (BufferedImage. tw th bi-type)
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
           (ImageIO/write dst ^String fmt baos)
           {:data       (bytes->base64 (.toByteArray baos))
            :width      tw
            :height     th
            :media-type (if (= fmt "jpeg") "image/jpeg" "image/png")})))))

;; ════════════════════════════════════════════════════════════════════
;; Babashka: pod-golang-image resize
;; ════════════════════════════════════════════════════════════════════

#?(:bb
   (do
     (def ^:private pod-loaded? (atom false))
     (def ^:private pod-path (atom nil))

     (defn set-pod-path!
       "Set the path to the pod-golang-image binary.
        If not set, searches PATH and ~/pod-golang-image/."
       [path]
       (reset! pod-path path))

     (defn- find-pod-binary []
       (or @pod-path
           (let [home (System/getProperty "user.home")
                 candidates ["pod-golang-image"
                             (str home "/pod-golang-image/pod-golang-image")]]
             (first (filter #(let [f (java.io.File. ^String %)]
                               (and (.exists f) (.canExecute f)))
                           candidates)))))

     (defn- ensure-pod!
       "Load the pod if available. Returns true if pod is ready."
       []
       (if @pod-loaded?
         true
         (when-let [bin (find-pod-binary)]
           (try
             (pods/load-pod bin)
             (require '[co.poyo.pod-golang-image])
             (reset! pod-loaded? true)
             true
             (catch Exception _ false)))))

     (defn- bb-resize
       "Resize using pod-golang-image. Returns result map or nil."
       [path opts]
       (when (ensure-pod!)
         (let [pod-fn (resolve 'co.poyo.pod-golang-image/resize)]
           (when pod-fn
             (pod-fn (str path) opts)))))

     (defn- bb-to-base64
       "Encode to base64 using pod-golang-image. Returns result map or nil."
       [path]
       (when (ensure-pod!)
         (let [pod-fn (resolve 'co.poyo.pod-golang-image/to-base64)]
           (when pod-fn
             (pod-fn (str path))))))))

;; Stub jvm-resize for bb so the unified dispatch compiles
#?(:bb (defn- jvm-resize [_ _] nil))

;; ════════════════════════════════════════════════════════════════════
;; Unified resize/encode dispatch
;; ════════════════════════════════════════════════════════════════════

(defn- resize-image
  "Resize an image file. Returns {:data :media-type :width :height}."
  [path opts]
  (or #?(:bb  (bb-resize path opts)
         :clj (jvm-resize path opts))
      ;; Fallback: no resize, just base64 the original
      (let [bs (slurp-bytes path)]
        {:data       (bytes->base64 bs)
         :media-type (or (mime-from-path path) "image/png")})))

(defn- encode-image
  "Encode an image file to base64 without resizing. Returns {:data :media-type}."
  [path]
  (or #?(:bb  (bb-to-base64 path)
         :clj nil)
      (let [bs (slurp-bytes path)]
        {:data       (bytes->base64 bs)
         :media-type (or (mime-from-path path) "image/png")})))

;; ════════════════════════════════════════════════════════════════════
;; Content part predicates
;; ════════════════════════════════════════════════════════════════════

(def ^:private content-types #{:image :pdf :text})

(defn content-part?
  "Returns true if x is a content part (image, pdf, or text part)."
  [x]
  (and (map? x)
       (boolean (content-types (:type x)))))

;; ════════════════════════════════════════════════════════════════════
;; Public API
;; ════════════════════════════════════════════════════════════════════

(defn text
  "Create a text content part for use in multimodal messages.

   (text \"What's in this image?\")"
  [s]
  {:type :text :text (str s)})

(defn image
  "Create an image content part. Source can be:
   - String/File path: reads, optionally resizes, base64-encodes
   - URL string (starts with http): passed by reference
   - byte array + media-type: raw bytes

   opts (optional):
     :max-edge   N  — scale so max(w,h) ≤ N
     :max-width  N  — scale so width ≤ N
     :max-height N  — scale so height ≤ N
     :format     \"png\"/\"jpeg\" — output format
     :quality    85 — JPEG quality (pod only)

   (image \"photo.jpg\")                       ; file, as-is
   (image \"photo.jpg\" {:max-edge 1024})      ; file, resized
   (image \"https://example.com/img.png\")     ; URL reference
   (image byte-array \"image/png\")            ; raw bytes"
  ([source] (image source {}))
  ([source opts-or-media-type]
   (cond
     ;; Raw bytes + media type string
     (and (bytes? source) (string? opts-or-media-type))
     {:type       :image
      :source     :base64
      :media-type opts-or-media-type
      :data       (bytes->base64 source)}

     ;; URL string
     (and (string? source)
          (or (.startsWith ^String source "http://")
              (.startsWith ^String source "https://")))
     (let [opts (if (map? opts-or-media-type) opts-or-media-type {})
           has-resize? (or (:max-edge opts) (:max-width opts) (:max-height opts))]
       (if has-resize?
         ;; Download, resize, return base64
         (let [url-ext (extension (.getPath (java.net.URL. source)))
               tmp (java.io.File/createTempFile "clj-llm-" (str "." (or url-ext "png")))
               _   (.deleteOnExit tmp)]
           (with-open [in  (.openStream (java.net.URL. source))
                       out (java.io.FileOutputStream. tmp)]
             (.transferTo in out))
           (let [result (resize-image (str tmp) opts)]
             {:type       :image
              :source     :base64
              :media-type (:media-type result)
              :data       (:data result)}))
         ;; No resize — pass URL through to model
         {:type   :image
          :source :url
          :url    source}))

     ;; File path (string or File)
     :else
     (let [path (str source)
           opts (if (map? opts-or-media-type) opts-or-media-type {})
           has-resize? (or (:max-edge opts) (:max-width opts) (:max-height opts))
           result (if has-resize?
                    (resize-image path opts)
                    (encode-image path))]
       {:type       :image
        :source     :base64
        :media-type (:media-type result)
        :data       (:data result)}))))

(defn pdf
  "Create a PDF content part from a file path.

   (pdf \"invoice.pdf\")"
  [path]
  (let [bs (slurp-bytes (str path))]
    {:type       :pdf
     :media-type "application/pdf"
     :data       (bytes->base64 bs)}))
