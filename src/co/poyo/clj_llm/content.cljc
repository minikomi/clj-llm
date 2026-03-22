(ns co.poyo.clj-llm.content
  (:require
   [clojure.string :as str]
   [co.poyo.clj-llm.impl.mime-util :as mime-util]
   [co.poyo.clj-llm.impl.bytes-util :as bytes-util]
   ;; bb must come first for override
   #?(:bb  [co.poyo.clj-llm.impl.image.bb :as impl]
      :clj [co.poyo.clj-llm.impl.image.jvm :as impl]))
  (:import
   [java.io File FileOutputStream]
   [java.net URL]))

;; ════════════════════════════════════════════════════════════════════
;; Image Encoding
;; ════════════════════════════════════════════════════════════════════

(defn- encode-image
  "Encode an image file to base64 without resizing.
   Returns {:data :media-type}."
  [path]
  (let [bs (bytes-util/slurp-bytes path)]
    {:data       (bytes-util/bytes->base64 bs)
     :media-type (or (mime-util/mime-from-path path) "image/png")}))

(defn- resize-opts?
  [opts]
  (boolean
   (or (:max-edge opts)
       (:max-width opts)
       (:max-height opts))))

(defn- url-string?
  [x]
  (and (string? x)
       (or (.startsWith ^String x "http://")
           (.startsWith ^String x "https://"))))

(defn- download-url->temp-file
  ^File [url]
  (let [url-obj (URL. url)
        url-ext (some-> (.getPath url-obj) mime-util/file-extension)
        tmp     (File/createTempFile "clj-llm-" (str "." (or url-ext "png")))]
    (.deleteOnExit tmp)
    (with-open [in  (.openStream url-obj)
                out (FileOutputStream. tmp)]
      (.transferTo in out))
    tmp))

;; ════════════════════════════════════════════════════════════════════
;; Content part predicates
;; ════════════════════════════════════════════════════════════════════

(def ^:private content-types #{:image :pdf :text})

(defn content-part?
  "Returns true if x is a content part."
  [x]
  (and (map? x)
       (boolean (content-types (:type x)))))

;; ════════════════════════════════════════════════════════════════════
;; Public API
;; ════════════════════════════════════════════════════════════════════

(defn text
  "Create a text content part.

   (text \"What's in this image?\")"
  [s]
  {:type :text
   :text (str s)})

(defn image
  "Create an image content part.

   Source can be:
   - String/File path: reads, optionally resizes, base64-encodes
   - URL string (starts with http): passed by reference unless resize requested
   - byte array + media-type: raw bytes

   opts:
     :max-edge   N      scale so max(width, height) <= N
     :max-width  N      scale so width <= N
     :max-height N      scale so height <= N
     :format     string output format, typically \"png\" or \"jpeg\"

   Examples:

     (image \"photo.jpg\")
     (image \"photo.jpg\" {:max-edge 1024})
     (image \"https://example.com/img.png\")
     (image some-bytes \"image/png\")"
  ([source]
   (image source {}))
  ([source opts-or-media-type]
   (cond
     ;; Raw bytes + media type
     (and (bytes? source) (string? opts-or-media-type))
     {:type       :image
      :source     :base64
      :media-type opts-or-media-type
      :data       (bytes-util/bytes->base64 source)}

     ;; URL source
     (url-string? source)
     (let [opts (if (map? opts-or-media-type) opts-or-media-type {})]
       (if (resize-opts? opts)
         (let [tmp    (download-url->temp-file source)
               result (impl/resize-image (str tmp) opts)]
           {:type       :image
            :source     :base64
            :media-type (:media-type result)
            :data       (:data result)})
         {:type   :image
          :source :url
          :url    source}))

     ;; File path or File-like object
     :else
     (let [path   (str source)
           opts   (if (map? opts-or-media-type) opts-or-media-type {})
           result (if (resize-opts? opts)
                    (impl/resize-image path opts)
                    (encode-image path))]
       {:type       :image
        :source     :base64
        :media-type (:media-type result)
        :data       (:data result)}))))

(defn pdf
  "Create a PDF content part from a file path.

   (pdf \"invoice.pdf\")"
  [path]
  (let [bs (bytes-util/slurp-bytes (str path))]
    {:type       :pdf
     :media-type "application/pdf"
     :data       (bytes-util/bytes->base64 bs)}))
