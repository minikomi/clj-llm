(ns co.poyo.clj-llm.net
  #?(:bb (:require [babashka.http-client :as http])
     :clj (:require [clojure.core.async :as a]))
  #?(:clj (:import (java.net.http HttpClient HttpClient$Version HttpRequest$BodyPublishers HttpRequest HttpResponse$BodyHandlers)
                   (java.net URI)
                   (java.time Duration))))

(defn post-stream
  "POST `url` with `headers` and string `body`.
   Calls `cb` with {:status int :body InputStream :error ex?}."
  [url headers body cb]
  #?(:bb
     (try
       (let [{:keys [status body error]} (http/post url {:headers headers
                                                         :body body
                                                         :as :stream
                                                         :throw false})]
         (cb {:status status :body body :error error}))
       (catch Exception e
         (cb {:status 0 :body nil :error e})))
     :clj
     ;; Use Java HTTP client in a future to avoid blocking
     (a/thread
       (try
         (let [client (-> (HttpClient/newBuilder)
                         (.version HttpClient$Version/HTTP_2)
                         (.connectTimeout (Duration/ofSeconds 10))
                         (.build))
               request-builder (-> (HttpRequest/newBuilder)
                                  (.uri (URI/create url))
                                  (.timeout (Duration/ofSeconds 30))
                                  (.POST (HttpRequest$BodyPublishers/ofString body)))]
           ;; Add headers
           (doseq [[k v] headers]
             (.header request-builder k v))

           (let [response (.send client
                                (.build request-builder)
                                (HttpResponse$BodyHandlers/ofInputStream))]
             (cb {:status (.statusCode response)
                  :body (.body response)
                  :error nil})))
         (catch Exception e
           (cb {:status 0 :body nil :error e}))))))