(ns co.poyo.clj-llm.impl.net.jvm
  (:import
   (java.net.http
    HttpClient
    HttpClient$Version
    HttpRequest
    HttpRequest$BodyPublishers
    HttpResponse$BodyHandlers)
   (java.net URI)
   (java.time Duration)))

(def ^:private http-client
  (delay
    (-> (HttpClient/newBuilder)
        (.version HttpClient$Version/HTTP_2)
        (.connectTimeout (Duration/ofSeconds 10))
        (.build))))

(defn post-stream
  "Blocking POST. Returns {:status int :body InputStream}.
   Throws on connection errors."
  [url headers body]
  (let [builder (-> (HttpRequest/newBuilder)
                    (.uri (URI/create url))
                    (.timeout (Duration/ofSeconds 30))
                    (.POST (HttpRequest$BodyPublishers/ofString body)))]
    (doseq [[k v] headers]
      (.header builder k v))
    (let [response (.send @http-client
                          (.build builder)
                          (HttpResponse$BodyHandlers/ofInputStream))]
      {:status (.statusCode response)
       :body   (.body response)})))
